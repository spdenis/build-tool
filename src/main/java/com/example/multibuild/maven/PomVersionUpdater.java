package com.example.multibuild.maven;

import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

@Component
public class PomVersionUpdater {

    private static final Logger log = LoggerFactory.getLogger(PomVersionUpdater.class);

    // Updates all pom.xml versions to <baseVersion>-SNAPSHOT (no branch qualifier).
    // Used for Lightspeed repos where the CI pipeline appends the branch name itself.
    // Returns the new version string.
    public String updateVersionsBare(Path repoDir) {
        List<Path> pomFiles = findPomFiles(repoDir);
        if (pomFiles.isEmpty()) {
            throw new RuntimeException("No pom.xml files found in " + repoDir);
        }
        RootInfo root = findRootInfo(pomFiles);
        if (root == null) {
            throw new RuntimeException("Could not determine root artifact in " + repoDir);
        }
        String newVersion = baseVersion(root.oldVersion()) + "-SNAPSHOT";
        log.info("Updating versions in {} from {} to {}", repoDir.getFileName(), root.oldVersion(), newVersion);
        for (Path pomFile : pomFiles) {
            updatePom(pomFile, root, newVersion);
        }
        return newVersion;
    }

    // Updates all pom.xml versions to <baseVersion>-<branchName>-SNAPSHOT,
    // preserving the numeric part of the existing version.
    // Returns the new version string (for use in the commit message).
    public String updateVersions(Path repoDir, String branchName) {
        List<Path> pomFiles = findPomFiles(repoDir);
        if (pomFiles.isEmpty()) {
            log.warn("No pom.xml files found in {}", repoDir);
            return branchName + "-SNAPSHOT";
        }

        RootInfo root = findRootInfo(pomFiles);
        if (root == null) {
            log.warn("Could not determine root artifact in {}", repoDir);
            return branchName + "-SNAPSHOT";
        }

        String newVersion = baseVersion(root.oldVersion()) + "-" + branchName + "-SNAPSHOT";
        log.info("Updating versions in {} from {} to {}", repoDir.getFileName(), root.oldVersion(), newVersion);
        for (Path pomFile : pomFiles) {
            updatePom(pomFile, root, newVersion);
        }
        return newVersion;
    }

    // Returns the current root version of the project in repoDir, or null if undetectable.
    public String getRootVersion(Path repoDir) {
        RootInfo root = findRootInfo(findPomFiles(repoDir));
        return root != null ? root.oldVersion() : null;
    }

    // Sets all module versions to an explicit version string.
    public void setVersions(Path repoDir, String newVersion) {
        List<Path> pomFiles = findPomFiles(repoDir);
        RootInfo root = findRootInfo(pomFiles);
        if (root == null) {
            log.warn("Could not determine root artifact in {}, skipping", repoDir);
            return;
        }
        log.info("Setting versions in {} to {}", repoDir.getFileName(), newVersion);
        for (Path pomFile : pomFiles) {
            updatePom(pomFile, root, newVersion);
        }
    }

    // Strips the qualifier suffix (everything from the first '-' onward).
    // e.g. "1.4.2-SNAPSHOT" → "1.4.2",  "2.0.0-RC1" → "2.0.0",  "1.0" → "1.0"
    private String baseVersion(String version) {
        int idx = version.indexOf('-');
        return idx >= 0 ? version.substring(0, idx) : version;
    }

    private void updatePom(Path pomPath, RootInfo root, String newVersion) {
        try {
            String originalXml = Files.readString(pomPath, StandardCharsets.UTF_8);
            Document doc = parseXml(originalXml);
            XPath xp = XPathFactory.newInstance().newXPath();
            boolean changed = false;

            // Update own <version> if it matches the root's old version
            Node ownVersion = node(xp, "/project/version", doc);
            if (ownVersion != null && root.oldVersion().equals(ownVersion.getTextContent().trim())) {
                ownVersion.setTextContent(newVersion);
                changed = true;
            }

            // Update <parent><version> if parent is the root of this project
            Node pg = node(xp, "/project/parent/groupId", doc);
            Node pa = node(xp, "/project/parent/artifactId", doc);
            Node pv = node(xp, "/project/parent/version", doc);
            if (pg != null && pa != null && pv != null
                    && root.groupId().equals(pg.getTextContent().trim())
                    && root.artifactId().equals(pa.getTextContent().trim())
                    && root.oldVersion().equals(pv.getTextContent().trim())) {
                pv.setTextContent(newVersion);
                changed = true;
            }

            if (changed) {
                writeXml(doc, pomPath, originalXml.stripLeading().startsWith("<?xml"));
                log.debug("Updated {}", pomPath.getFileName());
            }
        } catch (Exception e) {
            log.error("Failed to update {}: {}", pomPath, e.getMessage());
        }
    }

    // Identifies the project root: the pom with <modules> declared, or the
    // shallowest pom if none declare modules (single-module project).
    private RootInfo findRootInfo(List<Path> pomFiles) {
        MavenXpp3Reader reader = new MavenXpp3Reader();

        for (Path pomPath : pomFiles) {
            try (FileReader fr = new FileReader(pomPath.toFile())) {
                Model model = reader.read(fr);
                if (!model.getModules().isEmpty()) {
                    String groupId = model.getGroupId();
                    String artifactId = model.getArtifactId();
                    String version = model.getVersion();
                    if (groupId != null && artifactId != null && version != null) {
                        return new RootInfo(groupId, artifactId, version);
                    }
                }
            } catch (Exception e) {
                log.warn("Skipping {} during root detection: {}", pomPath, e.getMessage());
            }
        }

        // Single-module project: treat the first (shallowest) pom as root
        try (FileReader fr = new FileReader(pomFiles.get(0).toFile())) {
            Model model = reader.read(fr);
            String version = model.getVersion();
            if (version != null) {
                return new RootInfo(
                        model.getGroupId() != null ? model.getGroupId() : "",
                        model.getArtifactId() != null ? model.getArtifactId() : "",
                        version);
            }
        } catch (Exception e) {
            log.warn("Could not read root pom: {}", e.getMessage());
        }

        return null;
    }

    private List<Path> findPomFiles(Path repoDir) {
        Path gitDir = repoDir.resolve(".git");
        try (Stream<Path> paths = Files.walk(repoDir)) {
            return paths
                    .filter(p -> !p.startsWith(gitDir))
                    .filter(p -> p.getFileName().toString().equals("pom.xml"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new RuntimeException("Failed to scan " + repoDir, e);
        }
    }

    // --- XML helpers ---

    private static Node node(XPath xp, String expr, Object ctx) throws Exception {
        return (Node) xp.evaluate(expr, ctx, XPathConstants.NODE);
    }

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

    private record RootInfo(String groupId, String artifactId, String oldVersion) {}
}
