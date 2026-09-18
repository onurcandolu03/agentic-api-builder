package dev.agentic.harness;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/** Closed, bounded Surefire subset. Reports are untrusted data, never execution authority. */
final class OperationTestEvidence {
    static Map<String,Object> read(Path root, RepositoryFiles.Snapshot state, Map<String,List<String>> required) throws Exception {
        var reports = new ArrayList<Map<String,Object>>(); var tests = new TreeMap<String,String>();
        boolean failed = false;
        int retainedBytes = 0;
        for (var entry : state.entries()) {
            String path = (String)entry.get("pathKey");
            if (!path.matches("target/surefire-reports/TEST-[A-Za-z0-9_.$-]+\\.xml")) continue;
            if (reports.size() >= 64) throw new IllegalStateException("VALIDATION_REPORT_LIMIT");
            byte[] bytes;
            try (var stream = Files.newInputStream(root.resolve(path), LinkOption.NOFOLLOW_LINKS)) { bytes = stream.readNBytes(65537); }
            if (bytes.length > 65536 || !Json.fingerprint("PATH_CONTENT:" + path, bytes).equals(entry.get("contentFingerprint")))
                throw new IllegalStateException("VALIDATION_REPORT_CHANGED");
            if ((retainedBytes += bytes.length) > 65536) throw new IllegalStateException("VALIDATION_REPORT_LIMIT");
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
            var parser = factory.newDocumentBuilder();
            parser.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override public void error(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
                @Override public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; }
            });
            Element suite = parser.parse(new ByteArrayInputStream(bytes)).getDocumentElement();
            if (!suite.getTagName().equals("testsuite")) throw new IllegalStateException("VALIDATION_REPORT_FORMAT");
            var cases = suite.getElementsByTagName("testcase"); int failures = 0, errors = 0, skipped = 0;
            for (int i = 0; i < cases.getLength(); i++) {
                Element c = (Element)cases.item(i);
                if (c.getParentNode() != suite) throw new IllegalStateException("VALIDATION_REPORT_FORMAT");
                for (Node n = c.getFirstChild(); n != null; n = n.getNextSibling())
                    if (n instanceof Element child && !Set.of("failure", "error", "skipped", "system-out", "system-err").contains(child.getTagName()))
                        throw new IllegalStateException("VALIDATION_REPORT_FORMAT");
                String id = c.getAttribute("classname") + "#" + c.getAttribute("name");
                boolean f = c.getElementsByTagName("failure").getLength() > 0, e = c.getElementsByTagName("error").getLength() > 0,
                        s = c.getElementsByTagName("skipped").getLength() > 0;
                if (f) failures++; if (e) errors++; if (s) skipped++;
                if (tests.putIfAbsent(id, f || e ? "FAILED" : s ? "SKIPPED" : "PASSED") != null)
                    throw new IllegalStateException("VALIDATION_REPORT_DUPLICATE_TEST");
            }
            if (number(suite, "tests") != cases.getLength() || number(suite, "failures") != failures
                    || number(suite, "errors") != errors || number(suite, "skipped") != skipped)
                throw new IllegalStateException("VALIDATION_REPORT_COUNTS");
            failed |= failures + errors > 0;
            String exact = MigrationInput.utf8(bytes); Evidence.rejectObviousSecrets(exact);
            reports.add(Json.object("pathKey", path, "fingerprint", entry.get("contentFingerprint"), "exactText", exact,
                    "freshness", "CREATED_DURING_HOST_VALIDATION"));
        }
        var obligations = new ArrayList<Map<String,Object>>();
        boolean missing = false;
        for (var requirement : required.entrySet()) {
            boolean pass = requirement.getValue().stream().allMatch(id -> "PASSED".equals(tests.get(id)));
            missing |= !pass;
            obligations.add(Json.object("testObligationId", requirement.getKey(), "requiredTestIdentities", requirement.getValue(),
                    "outcome", pass ? "PASS" : "NOT_PROVEN"));
        }
        return Json.object("status", failed ? "FAILED" : missing ? "BLOCKED" : "SUCCESS",
                "code", failed ? "VALIDATION_REPORTED_TEST_FAILURE" : missing ? "VALIDATION_REQUIRED_TEST_EVIDENCE_MISSING" : "VALIDATION_PASSED",
                "reports", reports, "observedTests", tests, "testObligationResults", obligations);
    }
    private static int number(Element e, String key) {
        String value = e.getAttribute(key);
        if (!value.matches("[0-9]{1,6}")) throw new IllegalStateException("VALIDATION_REPORT_COUNTS");
        return Integer.parseInt(value);
    }
}
