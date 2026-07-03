package com.example.multibuild.maven;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

@Component
public class DependencyVersionUpdater {

    private static final Logger log = LoggerFactory.getLogger(DependencyVersionUpdater.class);

    private record PropRef(Path pomPath, Node node) {}

    // Updates <parent>, <dependency>, and <dependencyManagement> versions across all pom.xml
    // files for every in-scope artifact. Returns the repo roots that had at least one change.
    // Dependency versions specified via property placeholders (${prop}) are resolved through
    // <properties> — including when the property is defined in a parent pom within the same repo.
    // Parent versions are always inline — placeholders are not supported there.
    public Set<Path> update(List<Path> repoRoots, Map<String, String> versionByKey) {
        Set<Path> modifiedRepos = new LinkedHashSet<>();
        for (Path repoRoot : repoRoots) {
            if (updateRepo(repoRoot, versionByKey)) {
                modifiedRepos.add(repoRoot);
            }
        }
        return modifiedRepos;
    }

    private boolean updateRepo(Path repoRoot, Map<String, String> versionByKey) {
        List<Path> pomFiles = findPomFiles(repoRoot);
        if (pomFiles.isEmpty()) return false;

        Map<Path, String> originals = new LinkedHashMap<>();
        Map<Path, Document> docs = new LinkedHashMap<>();
        XPath xp = XPathFactory.newInstance().newXPath();

        for (Path pom : pomFiles) {
            try {
                String xml = Files.readString(pom, StandardCharsets.UTF_8);
                originals.put(pom, xml);
                docs.put(pom, XmlUtils.parseXml(xml));
            } catch (Exception e) {
                log.error("Failed to parse {}: {}", pom, e.getMessage());
            }
        }

        // Cross-pom property index so a placeholder in a child pom can resolve a
        // property defined only in the parent pom within the same repo.
        Map<String, PropRef> propIndex = buildPropIndex(docs, xp);

        Set<Path> changedPoms = new LinkedHashSet<>();
        // Shared across all poms in the repo to avoid double-writing the same property
        // when it is referenced by dependencies in multiple child modules.
        Map<String, String> updatedProps = new HashMap<>();

        for (Map.Entry<Path, Document> entry : docs.entrySet()) {
            Path pomPath = entry.getKey();
            Document doc = entry.getValue();
            try {
                if (updateParent(doc, xp, versionByKey)) changedPoms.add(pomPath);
                updateDepNodes(
                        (NodeList) xp.evaluate("/project/dependencies/dependency", doc, XPathConstants.NODESET),
                        xp, versionByKey, pomPath, propIndex, updatedProps, changedPoms);
                updateDepNodes(
                        (NodeList) xp.evaluate("/project/dependencyManagement/dependencies/dependency", doc, XPathConstants.NODESET),
                        xp, versionByKey, pomPath, propIndex, updatedProps, changedPoms);
            } catch (Exception e) {
                log.error("Failed to update dependency versions in {}: {}", pomPath, e.getMessage());
            }
        }

        for (Path pomPath : changedPoms) {
            try {
                String original = originals.get(pomPath);
                XmlUtils.writeXml(docs.get(pomPath), pomPath, original.stripLeading().startsWith("<?xml"));
                log.debug("Updated dependency versions in {}", pomPath);
            } catch (Exception e) {
                log.error("Failed to write {}: {}", pomPath, e.getMessage());
            }
        }

        return !changedPoms.isEmpty();
    }

    // Builds a map of property name → its location (pom path + live DOM node) across all poms
    // in the repo. When the same property is declared in multiple poms, the first occurrence
    // (by sorted path order — child dirs sort before the root pom.xml) is recorded; subsequent
    // definitions via putIfAbsent are skipped, matching Maven's child-overrides-parent semantics.
    private Map<String, PropRef> buildPropIndex(Map<Path, Document> docs, XPath xp) {
        Map<String, PropRef> index = new HashMap<>();
        for (Map.Entry<Path, Document> entry : docs.entrySet()) {
            try {
                Node propsNode = XmlUtils.node(xp, "/project/properties", entry.getValue());
                if (propsNode == null) continue;
                NodeList children = propsNode.getChildNodes();
                for (int i = 0; i < children.getLength(); i++) {
                    Node child = children.item(i);
                    if (child.getNodeType() == Node.ELEMENT_NODE) {
                        String name = child.getLocalName() != null ? child.getLocalName() : child.getNodeName();
                        index.putIfAbsent(name, new PropRef(entry.getKey(), child));
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to index properties in {}: {}", entry.getKey(), e.getMessage());
            }
        }
        return index;
    }

    private boolean updateParent(Document doc, XPath xp, Map<String, String> versionByKey) throws Exception {
        Node gId = XmlUtils.node(xp, "/project/parent/groupId", doc);
        Node aId = XmlUtils.node(xp, "/project/parent/artifactId", doc);
        Node ver = XmlUtils.node(xp, "/project/parent/version", doc);
        if (gId == null || aId == null || ver == null) return false;

        String key = gId.getTextContent().trim() + ":" + aId.getTextContent().trim();
        String newVersion = versionByKey.get(key);
        if (newVersion == null) return false;

        String current = ver.getTextContent().trim();
        if (newVersion.equals(current)) return false;

        log.info("  {}:{} (parent) {} -> {}", gId.getTextContent().trim(), aId.getTextContent().trim(), current, newVersion);
        ver.setTextContent(newVersion);
        return true;
    }

    private void updateDepNodes(NodeList nodes, XPath xp, Map<String, String> versionByKey,
                                Path currentPom, Map<String, PropRef> propIndex,
                                Map<String, String> updatedProps, Set<Path> changedPoms) throws Exception {
        for (int i = 0; i < nodes.getLength(); i++) {
            Node dep = nodes.item(i);
            Node gId = XmlUtils.node(xp, "groupId", dep);
            Node aId = XmlUtils.node(xp, "artifactId", dep);
            Node ver = XmlUtils.node(xp, "version", dep);
            if (gId == null || aId == null || ver == null) continue;

            String current = ver.getTextContent().trim();
            if (current.isEmpty()) continue;

            String key = gId.getTextContent().trim() + ":" + aId.getTextContent().trim();
            String newVersion = versionByKey.get(key);
            if (newVersion == null) continue;

            if (current.startsWith("${") && current.endsWith("}")) {
                String propName = current.substring(2, current.length() - 1);
                if (!updatedProps.containsKey(propName)) {
                    PropRef ref = propIndex.get(propName);
                    if (ref != null) {
                        String propValue = ref.node().getTextContent().trim();
                        if (!newVersion.equals(propValue)) {
                            log.info("  {}:{} property {} {} -> {}",
                                    gId.getTextContent().trim(), aId.getTextContent().trim(),
                                    propName, propValue, newVersion);
                            ref.node().setTextContent(newVersion);
                            changedPoms.add(ref.pomPath());
                        }
                        updatedProps.put(propName, newVersion);
                    } else {
                        log.warn("Property '{}' not found for {}:{}; skipping",
                                propName, gId.getTextContent().trim(), aId.getTextContent().trim());
                    }
                }
            } else {
                if (!newVersion.equals(current)) {
                    log.info("  {}:{} {} -> {}",
                            gId.getTextContent().trim(), aId.getTextContent().trim(), current, newVersion);
                    ver.setTextContent(newVersion);
                    changedPoms.add(currentPom);
                }
            }
        }
    }

    private List<Path> findPomFiles(Path repoRoot) {
        Path gitDir = repoRoot.resolve(".git");
        try (Stream<Path> paths = Files.walk(repoRoot)) {
            return paths
                    .filter(p -> !p.startsWith(gitDir))
                    .filter(p -> p.getFileName().toString().equals("pom.xml"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new RuntimeException("Failed to scan " + repoRoot, e);
        }
    }
}
