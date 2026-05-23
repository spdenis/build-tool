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

    // Updates <parent>, <dependency>, and <dependencyManagement> versions across all pom.xml
    // files for every in-scope artifact. Returns the repo roots that had at least one change.
    // Dependency versions specified via property placeholders (${prop}) are resolved through
    // <properties>. Parent versions are always inline — placeholders are not supported there.
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
        boolean anyChanged = false;
        for (Path pomFile : findPomFiles(repoRoot)) {
            anyChanged |= updatePom(pomFile, versionByKey);
        }
        return anyChanged;
    }

    private boolean updatePom(Path pomPath, Map<String, String> versionByKey) {
        try {
            String originalXml = Files.readString(pomPath, StandardCharsets.UTF_8);
            Document doc = XmlUtils.parseXml(originalXml);
            XPath xp = XPathFactory.newInstance().newXPath();
            // Track properties already updated in this pom to avoid double-writes when
            // the same property is referenced by more than one dependency.
            Map<String, String> updatedProps = new HashMap<>();

            boolean changed = updateParent(doc, xp, versionByKey);
            changed |= updateDepNodes(
                    (NodeList) xp.evaluate("/project/dependencies/dependency", doc, XPathConstants.NODESET),
                    xp, versionByKey, doc, updatedProps);
            changed |= updateDepNodes(
                    (NodeList) xp.evaluate("/project/dependencyManagement/dependencies/dependency", doc, XPathConstants.NODESET),
                    xp, versionByKey, doc, updatedProps);

            if (changed) {
                XmlUtils.writeXml(doc, pomPath, originalXml.stripLeading().startsWith("<?xml"));
                log.debug("Updated dependency versions in {}", pomPath);
            }
            return changed;
        } catch (Exception e) {
            log.error("Failed to update dependency versions in {}: {}", pomPath, e.getMessage());
            return false;
        }
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

    private boolean updateDepNodes(NodeList nodes, XPath xp, Map<String, String> versionByKey,
                                   Document doc, Map<String, String> updatedProps) throws Exception {
        boolean changed = false;
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
                // Version is a property placeholder — update the property definition instead
                String propName = current.substring(2, current.length() - 1);
                if (!updatedProps.containsKey(propName)) {
                    Node propNode = findProperty(doc, xp, propName);
                    if (propNode != null) {
                        String propValue = propNode.getTextContent().trim();
                        if (!newVersion.equals(propValue)) {
                            log.info("  {}:{} property {} {} -> {}",
                                    gId.getTextContent().trim(), aId.getTextContent().trim(),
                                    propName, propValue, newVersion);
                            propNode.setTextContent(newVersion);
                            changed = true;
                        }
                        updatedProps.put(propName, newVersion);
                    } else {
                        log.warn("Property '{}' not found in <properties> for {}:{}; skipping",
                                propName, gId.getTextContent().trim(), aId.getTextContent().trim());
                    }
                }
            } else {
                // Direct inline version
                if (!newVersion.equals(current)) {
                    log.info("  {}:{} {} -> {}",
                            gId.getTextContent().trim(), aId.getTextContent().trim(), current, newVersion);
                    ver.setTextContent(newVersion);
                    changed = true;
                }
            }
        }
        return changed;
    }

    // Finds a child element of /project/properties whose local name matches propName.
    // Using DOM traversal instead of XPath to handle any valid Maven property name.
    private static Node findProperty(Document doc, XPath xp, String propName) throws Exception {
        Node props = XmlUtils.node(xp, "/project/properties", doc);
        if (props == null) return null;
        NodeList children = props.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                String name = child.getLocalName() != null ? child.getLocalName() : child.getNodeName();
                if (propName.equals(name)) return child;
            }
        }
        return null;
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
