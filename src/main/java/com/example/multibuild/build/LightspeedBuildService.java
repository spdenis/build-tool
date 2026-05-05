package com.example.multibuild.build;

import com.example.multibuild.git.GitService;
import com.example.multibuild.model.Artifact;
import com.example.multibuild.model.BuildMode;
import com.example.multibuild.model.Module;
import com.example.multibuild.model.RepoConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Service
@Qualifier("lightspeed")
@EnableConfigurationProperties(LightspeedProperties.class)
public class LightspeedBuildService implements BuildService {

    private static final Logger log = LoggerFactory.getLogger(LightspeedBuildService.class);
    private static final String TRIGGER_PROP = "build.trigger.timestamp";

    @Value("${build.mode:SNAPSHOT}")
    private BuildMode buildMode;

    @Value("${dry.mode:false}")
    private boolean dryMode;

    @Value("${integration.branch:}")
    private String integrationBranch;

    private final LightspeedProperties props;
    private final GitService gitService;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    // Cached thread pool because poll loops are long-running blocking IO tasks
    private final java.util.concurrent.ExecutorService executor =
            Executors.newCachedThreadPool();

    public LightspeedBuildService(LightspeedProperties props, GitService gitService) {
        this.props = props;
        this.gitService = gitService;
    }

    @Override
    public void buildAll(List<List<Artifact>> layers, Map<Artifact, Module> moduleMap,
                         Map<Path, RepoConfig> repoConfigs, Set<String> completedRepoNames,
                         Map<Path, String> buildBranchByRepo) {
        boolean release = buildMode == BuildMode.RELEASE;
        if (release) {
            if (props.getMavenRepo().getReleasesUrl().isBlank()) {
                throw new RuntimeException(
                        "Lightspeed is not configured for release builds: set lightspeed.maven-repo.releases-url");
            }
        } else {
            if (props.getMavenRepo().getSnapshotsUrl().isBlank()) {
                throw new RuntimeException(
                        "Lightspeed is not configured for snapshot builds: set lightspeed.maven-repo.snapshots-url");
            }
        }

        Set<Path> overallSucceeded = new LinkedHashSet<>();

        for (List<Artifact> layer : layers) {
            Map<Path, List<Artifact>> repoArtifacts = new LinkedHashMap<>();
            for (Artifact a : layer) {
                Module m = moduleMap.get(a);
                if (m == null) continue;
                if (completedRepoNames.contains(m.getRepoRoot().getFileName().toString())) continue;
                repoArtifacts.computeIfAbsent(m.getRepoRoot(), k -> new ArrayList<>()).add(a);
            }
            if (repoArtifacts.isEmpty()) continue;

            Set<Path> layerSucceeded = ConcurrentHashMap.newKeySet();
            List<String> layerFailures = Collections.synchronizedList(new ArrayList<>());

            List<CompletableFuture<Void>> futures = repoArtifacts.entrySet().stream()
                    .map(e -> CompletableFuture.runAsync(() -> {
                        try {
                            buildRepo(e.getKey(), e.getValue(), release, buildBranchByRepo);
                            layerSucceeded.add(e.getKey());
                        } catch (RuntimeException ex) {
                            layerFailures.add(
                                    "Build failed in repository: " + e.getKey() + "\n" +
                                    "  Artifacts : " + e.getValue().stream()
                                            .map(Artifact::toString).collect(Collectors.joining(", ")) + "\n" +
                                    "  Reason    : " + ex.getMessage());
                        }
                    }, executor))
                    .toList();

            futures.forEach(CompletableFuture::join);
            overallSucceeded.addAll(layerSucceeded);

            if (!layerFailures.isEmpty()) {
                throw new BuildFailedException(
                        layerFailures.size() + " repo(s) failed:\n" +
                        String.join("\n---\n", layerFailures),
                        overallSucceeded);
            }
        }
    }

    private void buildRepo(Path repoRoot, List<Artifact> artifacts, boolean release,
                           Map<Path, String> buildBranchByRepo) {
        if (dryMode) {
            log.info("Dry mode — skipping Lightspeed build/poll for {}", repoRoot.getFileName());
            return;
        }

        if (release) {
            String releaseVersion = buildBranchByRepo.get(repoRoot);
            if (releaseVersion == null) {
                throw new RuntimeException(
                        "No release version found for " + repoRoot.getFileName() + " in buildBranchByRepo");
            }
            List<Artifact> releaseArtifacts = artifacts.stream()
                    .map(a -> new Artifact(a.getGroupId(), a.getArtifactId(), releaseVersion))
                    .toList();
            log.info("Polling Maven releases for {} artifact(s) in {} (version {})",
                    releaseArtifacts.size(), repoRoot.getFileName(), releaseVersion);
            pollUntilReleasePublished(repoRoot, releaseArtifacts);
        } else {
            // POM carries bare version (e.g. 1.0.1-SNAPSHOT); the pipeline publishes
            // under the full version (e.g. 1.0.1-integration-SNAPSHOT). Poll using that.
            List<Artifact> pollArtifacts = expandVersions(artifacts);
            log.info("Triggering and polling Maven snapshots for {} artifact(s) in {} (version {})",
                    pollArtifacts.size(), repoRoot.getFileName(),
                    pollArtifacts.isEmpty() ? "?" : pollArtifacts.get(0).getVersion());
            List<SnapshotMeta> baselines = captureSnapshotBaselines(pollArtifacts);
            injectTriggerAndPush(repoRoot);
            pollUntilSnapshotsUpdated(repoRoot, pollArtifacts, baselines);
        }
    }

    // --- Snapshot flow ---

    private List<SnapshotMeta> captureSnapshotBaselines(List<Artifact> artifacts) {
        return artifacts.stream()
                .map(a -> {
                    String url = snapshotMetadataUrl(a);
                    String xml = fetchXml(url);
                    SnapshotMeta meta = xml != null ? parseSnapshotMeta(xml) : SnapshotMeta.EMPTY;
                    log.info("  [LS] Baseline {}:{} build#{}", a.getArtifactId(), meta.timestamp(), meta.buildNumber());
                    return meta;
                })
                .toList();
    }

    private void injectTriggerAndPush(Path repoRoot) {
        Path pomPath = repoRoot.resolve("pom.xml");
        if (!Files.exists(pomPath)) {
            log.warn("No root pom.xml in {}, skipping trigger injection", repoRoot.getFileName());
            return;
        }
        try {
            injectTimestamp(pomPath);
        } catch (Exception e) {
            log.warn("Could not inject build trigger into {}: {}", pomPath, e.getMessage());
        }
        gitService.commitAllIfDirty(repoRoot, "chore: trigger ci build");
        gitService.push(repoRoot);
        log.info("Pushed trigger commit for {}", repoRoot.getFileName());
    }

    private void pollUntilSnapshotsUpdated(Path repoRoot, List<Artifact> artifacts,
                                           List<SnapshotMeta> baselines) {
        long startMs = System.currentTimeMillis();
        long deadline = startMs + props.getTimeoutMs();
        while (System.currentTimeMillis() < deadline) {
            sleep(props.getPollIntervalMs());
            int pending = 0;
            for (int i = 0; i < artifacts.size(); i++) {
                Artifact a = artifacts.get(i);
                SnapshotMeta baseline = baselines.get(i);
                String xml = fetchXml(snapshotMetadataUrl(a));
                if (xml == null) { pending++; continue; }
                SnapshotMeta current = parseSnapshotMeta(xml);
                if (!current.isNewerThan(baseline)) {
                    pending++;
                } else {
                    log.debug("Snapshot updated: {} build#{}", a.getArtifactId(), current.buildNumber());
                }
            }
            if (pending == 0) {
                log.info("  [LS] All {} snapshot artifact(s) published for {} in {}",
                        artifacts.size(), repoRoot.getFileName(), elapsed(startMs));
                return;
            }
            log.info("  [LS] Waiting for {} artifact(s) in {} (elapsed={})",
                    pending, repoRoot.getFileName(), elapsed(startMs));
        }
        throw new RuntimeException("Timeout waiting for Maven snapshot artifacts in " + repoRoot.getFileName());
    }

    // --- Release flow ---

    private void pollUntilReleasePublished(Path repoRoot, List<Artifact> artifacts) {
        long startMs = System.currentTimeMillis();
        long deadline = startMs + props.getTimeoutMs();
        while (System.currentTimeMillis() < deadline) {
            sleep(props.getPollIntervalMs());
            List<Artifact> pending = artifacts.stream().filter(a -> !isReleasePublished(a)).toList();
            if (pending.isEmpty()) {
                log.info("  [LS] All {} release artifact(s) published for {} in {}",
                        artifacts.size(), repoRoot.getFileName(), elapsed(startMs));
                return;
            }
            log.info("  [LS] Waiting for {}/{} artifact(s) in {} (elapsed={})",
                    pending.size(), artifacts.size(), repoRoot.getFileName(), elapsed(startMs));
        }
        List<String> missing = artifacts.stream()
                .filter(a -> !isReleasePublished(a))
                .map(a -> a.getGroupId() + ":" + a.getArtifactId() + ":" + a.getVersion())
                .toList();
        throw new RuntimeException("Timeout waiting for release artifacts in " + repoRoot.getFileName() +
                ". Missing: " + missing);
    }

    private boolean isReleasePublished(Artifact a) {
        String url = releasePomUrl(a);
        try {
            HttpResponse<Void> resp = httpClient.send(
                    requestBuilder(url).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            log.debug("HEAD {} failed: {}", url, e.getMessage());
            return false;
        }
    }

    // --- Timestamp injection ---

    private void injectTimestamp(Path pomPath) throws Exception {
        String originalXml = Files.readString(pomPath, StandardCharsets.UTF_8);
        Document doc = parseXml(originalXml);
        XPath xp = XPathFactory.newInstance().newXPath();

        String timestamp = Instant.now().toString();

        Node props = (Node) xp.evaluate("/project/properties", doc, XPathConstants.NODE);
        if (props == null) {
            // Create <properties> and append to <project>
            Node project = (Node) xp.evaluate("/project", doc, XPathConstants.NODE);
            if (project == null) return;
            props = doc.createElement("properties");
            project.appendChild(doc.createTextNode("\n    "));
            project.appendChild(props);
            project.appendChild(doc.createTextNode("\n"));
        }

        // Find or create the trigger property child element
        Node triggerNode = findChildElement(props, TRIGGER_PROP);
        if (triggerNode == null) {
            triggerNode = doc.createElement(TRIGGER_PROP);
            props.appendChild(doc.createTextNode("\n        "));
            props.appendChild(triggerNode);
            props.appendChild(doc.createTextNode("\n    "));
        }
        triggerNode.setTextContent(timestamp);

        writeXml(doc, pomPath, originalXml.stripLeading().startsWith("<?xml"));
        log.debug("Injected build trigger timestamp {} into {}", timestamp, pomPath.getFileName());
    }

    private static Node findChildElement(Node parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                String name = child.getLocalName() != null ? child.getLocalName() : child.getNodeName();
                if (localName.equals(name)) return child;
            }
        }
        return null;
    }

    // --- Maven metadata parsing ---

    private SnapshotMeta parseSnapshotMeta(String xml) {
        try {
            Document doc = parseXml(xml);
            XPath xp = XPathFactory.newInstance().newXPath();
            Node ts = (Node) xp.evaluate("/metadata/versioning/snapshot/timestamp", doc, XPathConstants.NODE);
            Node bn = (Node) xp.evaluate("/metadata/versioning/snapshot/buildNumber", doc, XPathConstants.NODE);
            String timestamp = ts != null ? ts.getTextContent().trim() : "";
            int buildNumber = 0;
            if (bn != null) {
                try { buildNumber = Integer.parseInt(bn.getTextContent().trim()); } catch (NumberFormatException ignored) {}
            }
            return new SnapshotMeta(timestamp, buildNumber);
        } catch (Exception e) {
            log.warn("Failed to parse snapshot metadata XML: {}", e.getMessage());
            return SnapshotMeta.EMPTY;
        }
    }

    // --- URL helpers ---

    private String snapshotMetadataUrl(Artifact a) {
        return props.getMavenRepo().getSnapshotsUrl().stripTrailing() + "/" +
                groupPath(a.getGroupId()) + "/" + a.getArtifactId() + "/" +
                a.getVersion() + "/maven-metadata.xml";
    }

    private String releasePomUrl(Artifact a) {
        return props.getMavenRepo().getReleasesUrl().stripTrailing() + "/" +
                groupPath(a.getGroupId()) + "/" + a.getArtifactId() + "/" +
                a.getVersion() + "/" + a.getArtifactId() + "-" + a.getVersion() + ".pom";
    }

    private static String groupPath(String groupId) {
        return groupId.replace('.', '/');
    }

    // --- HTTP helpers ---

    private String fetchXml(String url) {
        try {
            HttpResponse<String> resp = httpClient.send(
                    requestBuilder(url).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 404) return null;
            if (resp.statusCode() >= 300) {
                log.warn("Unexpected status {} from {}", resp.statusCode(), url);
                return null;
            }
            return resp.body();
        } catch (Exception e) {
            log.warn("Failed to fetch {}: {}", url, e.getMessage());
            return null;
        }
    }

    private HttpRequest.Builder requestBuilder(String url) {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/xml");
        String user = props.getMavenRepo().getUsername();
        if (!user.isBlank()) {
            String creds = Base64.getEncoder().encodeToString(
                    (user + ":" + props.getMavenRepo().getPassword()).getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + creds);
        }
        return builder;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private List<Artifact> expandVersions(List<Artifact> artifacts) {
        if (integrationBranch.isBlank()) return artifacts;
        return artifacts.stream()
                .map(a -> {
                    String v = a.getVersion();
                    if (v.endsWith("-SNAPSHOT")) {
                        v = v.substring(0, v.length() - "-SNAPSHOT".length())
                                + "-" + integrationBranch + "-SNAPSHOT";
                    }
                    return new Artifact(a.getGroupId(), a.getArtifactId(), v);
                })
                .toList();
    }

    private static String elapsed(long startMs) {
        long s = (System.currentTimeMillis() - startMs) / 1000;
        return String.format("%d:%02d", s / 60, s % 60);
    }

    // --- XML helpers ---

    private static Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        return dbf.newDocumentBuilder().parse(new org.xml.sax.InputSource(new StringReader(xml)));
    }

    private static void writeXml(Document doc, Path path, boolean includeDeclaration) throws Exception {
        Transformer t = TransformerFactory.newInstance().newTransformer();
        t.setOutputProperty(OutputKeys.INDENT, "no");
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, includeDeclaration ? "no" : "yes");
        try (OutputStream os = Files.newOutputStream(path)) {
            t.transform(new DOMSource(doc), new StreamResult(os));
        }
    }

    private record SnapshotMeta(String timestamp, int buildNumber) {
        static final SnapshotMeta EMPTY = new SnapshotMeta("", 0);

        boolean isNewerThan(SnapshotMeta other) {
            if (this.buildNumber != other.buildNumber) return this.buildNumber > other.buildNumber;
            return this.timestamp.compareTo(other.timestamp) > 0;
        }
    }
}
