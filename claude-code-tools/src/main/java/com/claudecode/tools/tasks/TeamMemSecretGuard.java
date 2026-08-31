package com.claudecode.tools.tasks;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-side secret scanner for team-memory writes.
 */
public final class TeamMemSecretGuard {

    private static final Logger LOG = LoggerFactory.getLogger(TeamMemSecretGuard.class);

    /**
     * Trailing boundary used by most gitleaks rules: a backtick, quote,
     * semicolon, whitespace, an escaped newline, or end-of-string. Keeps the
     * prefix from matching inside a longer alphanumeric run.
     */
    private static final String BOUNDARY = "(?:[\\x60'\"\\s;]|\\\\[nr]|$)";

    /** Anthropic key prefix, assembled at runtime so the literal byte sequence isn't present in the class file. */
    private static final String ANT_KEY_PFX = "sk" + "-" + "ant" + "-" + "api";

    /** Max safe length for a key's base64-ish tail we don't want to expand unbounded. */
    private record Rule(String id, String source, String flags) {}

    private static final List<Rule> RULES = List.of(
        new Rule("aws-access-token", "\\b((?:A3T[A-Z0-9]|AKIA|ASIA|ABIA|ACCA)[A-Z2-7]{16})\\b", null),
        new Rule("gcp-api-key", "\\b(AIza[\\w-]{35})" + BOUNDARY, null),
        new Rule("azure-ad-client-secret",
            "(?:^|[\\\\'\"\\x60\\s>=:(,)])([a-zA-Z0-9_~.]{3}\\dQ~[a-zA-Z0-9_~.-]{31,34})(?:$|[\\\\'\"\\x60\\s<),])", null),
        new Rule("digitalocean-pat", "\\b(dop_v1_[a-f0-9]{64})" + BOUNDARY, null),
        new Rule("digitalocean-access-token", "\\b(doo_v1_[a-f0-9]{64})" + BOUNDARY, null),
        new Rule("anthropic-api-key", "\\b(" + ANT_KEY_PFX + "03-[a-zA-Z0-9_\\-]{93}AA)" + BOUNDARY, null),
        new Rule("anthropic-admin-api-key", "\\b(sk-ant-admin01-[a-zA-Z0-9_\\-]{93}AA)" + BOUNDARY, null),
        new Rule("openai-api-key",
            "\\b(sk-(?:proj|svcacct|admin)-(?:[A-Za-z0-9_-]{74}|[A-Za-z0-9_-]{58})T3BlbkFJ(?:[A-Za-z0-9_-]{74}|[A-Za-z0-9_-]{58})\\b|sk-[a-zA-Z0-9]{20}T3BlbkFJ[a-zA-Z0-9]{20})" + BOUNDARY, null),
        new Rule("huggingface-access-token", "\\b(hf_[a-zA-Z]{34})" + BOUNDARY, null),
        new Rule("github-pat", "ghp_[0-9a-zA-Z]{36}", null),
        new Rule("github-fine-grained-pat", "github_pat_\\w{82}", null),
        new Rule("github-app-token", "(?:ghu|ghs)_[0-9a-zA-Z]{36}", null),
        new Rule("github-oauth", "gho_[0-9a-zA-Z]{36}", null),
        new Rule("github-refresh-token", "ghr_[0-9a-zA-Z]{36}", null),
        new Rule("gitlab-pat", "glpat-[\\w-]{20}", null),
        new Rule("gitlab-deploy-token", "gldt-[0-9a-zA-Z_\\-]{20}", null),
        new Rule("slack-bot-token", "xoxb-[0-9]{10,13}-[0-9]{10,13}[a-zA-Z0-9-]*", null),
        new Rule("slack-user-token", "xox[pe](?:-[0-9]{10,13}){3}-[a-zA-Z0-9-]{28,34}", null),
        new Rule("slack-app-token", "xapp-\\d-[A-Z0-9]+-\\d+-[a-z0-9]+", "i"),
        new Rule("twilio-api-key", "SK[0-9a-fA-F]{32}", null),
        new Rule("sendgrid-api-token", "\\b(SG\\.[a-zA-Z0-9=_\\-.]{66})" + BOUNDARY, null),
        new Rule("npm-access-token", "\\b(npm_[a-zA-Z0-9]{36})" + BOUNDARY, null),
        new Rule("pypi-upload-token", "pypi-AgEIcHlwaS5vcmc[\\w-]{50,1000}", null),
        new Rule("databricks-api-token", "\\b(dapi[a-f0-9]{32}(?:-\\d)?)" + BOUNDARY, null),
        new Rule("hashicorp-tf-api-token", "[a-zA-Z0-9]{14}\\.atlasv1\\.[a-zA-Z0-9\\-_=]{60,70}", null),
        new Rule("pulumi-api-token", "\\b(pul-[a-f0-9]{40})" + BOUNDARY, null),
        new Rule("postman-api-token", "\\b(PMAK-[a-fA-F0-9]{24}-[a-fA-F0-9]{34})" + BOUNDARY, null),
        new Rule("grafana-api-key", "\\b(eyJrIjoi[A-Za-z0-9+/]{70,400}={0,3})" + BOUNDARY, null),
        new Rule("grafana-cloud-api-token", "\\b(glc_[A-Za-z0-9+/]{32,400}={0,3})" + BOUNDARY, null),
        new Rule("grafana-service-account-token", "\\b(glsa_[A-Za-z0-9]{32}_[A-Fa-f0-9]{8})" + BOUNDARY, null),
        new Rule("sentry-user-token", "\\b(sntryu_[a-f0-9]{64})" + BOUNDARY, null),
        new Rule("sentry-org-token",
            "\\bsntrys_eyJpYXQiO[a-zA-Z0-9+/]{10,200}(?:LCJyZWdpb25fdXJs|InJlZ2lvbl91cmwi|cmVnaW9uX3VybCI6)[a-zA-Z0-9+/]{10,200}={0,2}_[a-zA-Z0-9+/]{43}", null),
        new Rule("stripe-access-token", "\\b((?:sk|rk)_(?:test|live|prod)_[a-zA-Z0-9]{10,99})" + BOUNDARY, null),
        new Rule("shopify-access-token", "shpat_[a-fA-F0-9]{32}", null),
        new Rule("shopify-shared-secret", "shpss_[a-fA-F0-9]{32}", null),
        new Rule("private-key",
            "-----BEGIN[ A-Z0-9_-]{0,100}PRIVATE KEY(?: BLOCK)?-----[\\s\\S-]{64,}?-----END[ A-Z0-9_-]{0,100}PRIVATE KEY(?: BLOCK)?-----", "i")
    );

    private static final Map<String, String> SPECIAL_CASE = Map.ofEntries(
        Map.entry("aws", "AWS"),
        Map.entry("gcp", "GCP"),
        Map.entry("api", "API"),
        Map.entry("pat", "PAT"),
        Map.entry("ad", "AD"),
        Map.entry("tf", "TF"),
        Map.entry("oauth", "OAuth"),
        Map.entry("npm", "NPM"),
        Map.entry("pypi", "PyPI"),
        Map.entry("jwt", "JWT"),
        Map.entry("github", "GitHub"),
        Map.entry("gitlab", "GitLab"),
        Map.entry("openai", "OpenAI"),
        Map.entry("digitalocean", "DigitalOcean"),
        Map.entry("huggingface", "HuggingFace"),
        Map.entry("hashicorp", "HashiCorp"),
        Map.entry("sendgrid", "SendGrid")
    );

    /** A matched gitleaks rule (label only — the matched text is never exposed). */
    public record SecretMatch(String ruleId, String label) {}

    private static final List<Pattern> COMPILED = compileRules();

    private TeamMemSecretGuard() {}

    private static List<Pattern> compileRules() {
        List<Pattern> compiled = new ArrayList<>(RULES.size());
        for (Rule rule : RULES) {
            try {
                int flags = Strings.CS.equals("i", rule.flags()) ? Pattern.CASE_INSENSITIVE : 0;
                compiled.add(Pattern.compile(rule.source(), flags));
            } catch (RuntimeException e) {
                // Defensive: a malformed rule must not disable the whole guard.
                LOG.warn("Skipping invalid team-mem secret rule {}: {}", rule.id(), e.getMessage());
                compiled.add(null);
            }
        }
        return compiled;
    }

    /**
     * Scan content for potential secrets. Returns one match per rule that
     * fired (deduplicated by rule id). The matched text is intentionally not
     * returned.
     */
    public static List<SecretMatch> scanForSecrets(String content) {
        List<SecretMatch> matches = new ArrayList<>();
        if (StringUtils.isEmpty(content)) {
            return matches;
        }
        for (int i = 0; i < RULES.size(); i++) {
            Pattern pattern = COMPILED.get(i);
            if (pattern == null) {
                continue;
            }
            if (pattern.matcher(content).find()) {
                String ruleId = RULES.get(i).id();
                matches.add(new SecretMatch(ruleId, ruleIdToLabel(ruleId)));
            }
        }
        return matches;
    }

    /**
     * If {@code filePath} resolves inside the team-memory directory for
     * {@code workingDirectory} and {@code content} contains a high-confidence
     * secret, return a blocking error message. Otherwise return {@code null}.
     *
     * @param filePath         the target path (absolute or relative); resolved
     *                         and normalized internally.
     * @param content          the content about to be written.
     * @param workingDirectory the engine's working directory (used to locate
     *                         the team-memory directory).
     */
    public static String checkTeamMemSecrets(String filePath, String content, String workingDirectory) {
        if (!TeamMemPaths.isTeamMemPath(filePath, workingDirectory)) {
            return null;
        }
        List<SecretMatch> matches = scanForSecrets(content);
        if (matches.isEmpty()) {
            return null;
        }
        String labels = matches.stream().map(SecretMatch::label).collect(Collectors.joining(", "));
        return "Content contains potential secrets (" + labels + ") and cannot be written to team memory. "
            + "Team memory is shared with all repository collaborators. "
            + "Remove the sensitive content and try again.";
    }

    static String ruleIdToLabel(String ruleId) {
        StringBuilder sb = new StringBuilder();
        for (String part : ruleId.split("-")) {
            if (SPECIAL_CASE.containsKey(part)) {
                sb.append(SPECIAL_CASE.get(part));
            } else {
                sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
            sb.append(' ');
        }
        // Trim the trailing space; handle empty defensively.
        String label = sb.toString();
        return label.isEmpty() ? ruleId : label.substring(0, label.length() - 1);
    }
}
