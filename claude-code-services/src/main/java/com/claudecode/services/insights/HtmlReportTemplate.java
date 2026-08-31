package com.claudecode.services.insights;


/**
 * Verbatim HTML / CSS / JS template fragments for the {@code /insights} HTML report.
 */
final class HtmlReportTemplate {

    private HtmlReportTemplate() {}

    // ------------------------------------------------------------------

    // ------------------------------------------------------------------

    /** Shared by generateBarChart (dynamic color) and the response-time histogram (#6366f1). */
    static final String BAR_ROW = """
<div class="bar-row">
        <div class="bar-label">${label}</div>
        <div class="bar-track"><div class="bar-fill" style="width:${pct}%;background:${color}"></div></div>
        <div class="bar-value">${count}</div>
      </div>""";

    static final String TIME_BAR_ROW = "\n      " + """
<div class="bar-row">
        <div class="bar-label">${label}</div>
        <div class="bar-track"><div class="bar-fill" style="width:${pct}%;background:#8b5cf6"></div></div>
        <div class="bar-value">${count}</div>
      </div>""";

// ------------------------------------------------------------------ At a Glance.

    static final String AT_A_GLANCE = """

    <div class="at-a-glance">
      <div class="glance-title">At a Glance</div>
      <div class="glance-sections">
        ${working}
        ${hindering}
        ${quick_wins}
        ${ambitious}
      </div>
    </div>
""" + "    ";

    static final String GLANCE_WORKING =
        "<div class=\"glance-section\"><strong>What's working:</strong> ${text} "
            + "<a href=\"#section-wins\" class=\"see-more\">Impressive Things You Did →</a></div>";

    static final String GLANCE_HINDERING =
        "<div class=\"glance-section\"><strong>What's hindering you:</strong> ${text} "
            + "<a href=\"#section-friction\" class=\"see-more\">Where Things Go Wrong →</a></div>";

    static final String GLANCE_QUICK_WINS =
        "<div class=\"glance-section\"><strong>Quick wins to try:</strong> ${text} "
            + "<a href=\"#section-features\" class=\"see-more\">Features to Try →</a></div>";

    static final String GLANCE_AMBITIOUS =
        "<div class=\"glance-section\"><strong>Ambitious workflows:</strong> ${text} "
            + "<a href=\"#section-horizon\" class=\"see-more\">On the Horizon →</a></div>";

// ------------------------------------------------------------------ Project areas.

    static final String PROJECT_AREAS = """

    <h2 id="section-work">What You Work On</h2>
    <div class="project-areas">
      ${items}
    </div>
""" + "    ";

    static final String PROJECT_AREA_ITEM = """

        <div class="project-area">
          <div class="area-header">
            <span class="area-name">${name}</span>
            <span class="area-count">~${session_count} sessions</span>
          </div>
          <div class="area-desc">${description}</div>
        </div>
""" + "      ";

// ------------------------------------------------------------------ Interaction style.

    static final String INTERACTION = """

    <h2 id="section-usage">How You Use Claude Code</h2>
    <div class="narrative">
      ${narrative}
      ${key_pattern}
    </div>
""" + "    ";

    static final String KEY_PATTERN_LINE =
        "<div class=\"key-insight\"><strong>Key pattern:</strong> ${text}</div>";

// ------------------------------------------------------------------ What works.

    static final String WHAT_WORKS = """

    <h2 id="section-wins">Impressive Things You Did</h2>
    ${intro}
    <div class="big-wins">
      ${items}
    </div>
""" + "    ";

    /** Shared intro line for what-works / friction / horizon sections. */
    static final String SECTION_INTRO = "<p class=\"section-intro\">${text}</p>";

    static final String BIG_WIN_ITEM = """

        <div class="big-win">
          <div class="big-win-title">${title}</div>
          <div class="big-win-desc">${description}</div>
        </div>
""" + "      ";

// ------------------------------------------------------------------ Friction.

    static final String FRICTION = """

    <h2 id="section-friction">Where Things Go Wrong</h2>
    ${intro}
    <div class="friction-categories">
      ${items}
    </div>
""" + "    ";

    static final String FRICTION_ITEM = """

        <div class="friction-category">
          <div class="friction-title">${category}</div>
          <div class="friction-desc">${description}</div>
          ${examples}
        </div>
""" + "      ";

// ------------------------------------------------------------------ Suggestions.

    static final String SUGGESTIONS = """

    ${claude_md}
    ${features}
    ${patterns}
""" + "    ";

    static final String CLAUDE_MD_BLOCK = """

    <h2 id="section-features">Existing CC Features to Try</h2>
    <div class="claude-md-section">
      <h3>Suggested CLAUDE.md Additions</h3>
      <p style="font-size: 12px; color: #64748b; margin-bottom: 12px;">Just copy this into Claude Code to add it to your CLAUDE.md.</p>
      <div class="claude-md-actions">
        <button class="copy-all-btn" onclick="copyAllCheckedClaudeMd()">Copy All Checked</button>
      </div>
      ${items}
    </div>
""" + "    ";


    // output; the browser-side copy handlers paste it verbatim.
    static final String CLAUDE_MD_ITEM = """

        <div class="claude-md-item">
          <input type="checkbox" id="cmd-${i}" class="cmd-checkbox" checked data-text="${data_head}\\n\\n${addition}">
          <label for="cmd-${i}">
            <code class="cmd-code">${addition}</code>
            <button class="copy-btn" onclick="copyCmdItem(${i})">Copy</button>
          </label>
          <div class="cmd-why">${why}</div>
        </div>
""" + "      ";

    static final String FEATURES_BLOCK = """

    <p style="font-size: 13px; color: #64748b; margin-bottom: 12px;">Just copy this into Claude Code and it'll set it up for you.</p>
    <div class="features-section">
      ${items}
    </div>
""" + "    ";

    static final String FEATURE_ITEM = """

        <div class="feature-card">
          <div class="feature-title">${feature}</div>
          <div class="feature-oneliner">${one_liner}</div>
          <div class="feature-why"><strong>Why for you:</strong> ${why_for_you}</div>
          ${example}
        </div>
""" + "      ";

    static final String FEATURE_EXAMPLE = """

          <div class="feature-examples">
            <div class="feature-example">
              <div class="example-code-row">
                <code class="example-code">${example_code}</code>
                <button class="copy-btn" onclick="copyText(this)">Copy</button>
              </div>
            </div>
          </div>
""" + "          ";

    static final String PATTERNS_BLOCK = """

    <h2 id="section-patterns">New Ways to Use Claude Code</h2>
    <p style="font-size: 13px; color: #64748b; margin-bottom: 12px;">Just copy this into Claude Code and it'll walk you through it.</p>
    <div class="patterns-section">
      ${items}
    </div>
""" + "    ";

    static final String PATTERN_ITEM = """

        <div class="pattern-card">
          <div class="pattern-title">${title}</div>
          <div class="pattern-summary">${suggestion}</div>
          ${detail}
          ${prompt}
        </div>
""" + "      ";

    static final String PATTERN_DETAIL_LINE = "<div class=\"pattern-detail\">${text}</div>";

    static final String PATTERN_PROMPT = """

          <div class="copyable-prompt-section">
            <div class="prompt-label">Paste into Claude Code:</div>
            <div class="copyable-prompt-row">
              <code class="copyable-prompt">${copyable_prompt}</code>
              <button class="copy-btn" onclick="copyText(this)">Copy</button>
            </div>
          </div>
""" + "          ";

// ------------------------------------------------------------------ On the Horizon.

    static final String HORIZON = """

    <h2 id="section-horizon">On the Horizon</h2>
    ${intro}
    <div class="horizon-section">
      ${items}
    </div>
""" + "    ";

    static final String HORIZON_ITEM = """

        <div class="horizon-card">
          <div class="horizon-title">${title}</div>
          <div class="horizon-possible">${whats_possible}</div>
          ${tip}
          ${prompt}
        </div>
""" + "      ";

    static final String HORIZON_TIP_LINE =
        "<div class=\"horizon-tip\"><strong>Getting started:</strong> ${text}</div>";

    static final String HORIZON_PROMPT_LINE =
        "<div class=\"pattern-prompt\"><div class=\"prompt-label\">Paste into Claude Code:</div>"
            + "<code>${text}</code><button class=\"copy-btn\" onclick=\"copyText(this)\">Copy</button></div>";

    // ------------------------------------------------------------------

    // ------------------------------------------------------------------

    static final String TEAM_FEEDBACK = """

    <h2 id="section-feedback" class="feedback-header">Closing the Loop: Feedback for Other Teams</h2>
    <p class="feedback-intro">Suggestions for the CC product and model teams based on your usage patterns. Click to expand.</p>
    ${cc_block}
    ${model_block}
""" + "    ";

    static final String COLLAPSIBLE_BLOCK = """

    <div class="collapsible-section">
      <div class="collapsible-header" onclick="toggleCollapsible(this)">
        <span class="collapsible-arrow">▶</span>
        <h3>${heading}</h3>
      </div>
      <div class="collapsible-content">
        <div class="suggestions-section">
          ${items}
        </div>
      </div>
    </div>
""" + "    ";

    static final String FEEDBACK_ITEM = """

            <div class="feedback-card ${card_class}">
              <div class="feedback-title">${title}</div>
              <div class="feedback-detail">${detail}</div>
              ${evidence}
            </div>
""" + "          ";

    static final String FEEDBACK_EVIDENCE_LINE =
        "<div class=\"feedback-evidence\"><em>Evidence:</em> ${text}</div>";

// ------------------------------------------------------------------ Fun ending.

    static final String FUN_ENDING = """

    <div class="fun-ending">
      <div class="fun-headline">"${headline}"</div>
      ${detail}
    </div>
""" + "    ";

    static final String FUN_DETAIL_LINE = "<div class=\"fun-detail\">${text}</div>";

// ------------------------------------------------------------------ Multi-clauding branches.

    static final String MULTI_CLAUDING_NONE = """

        <p style="font-size: 14px; color: #64748b; padding: 8px 0;">
          No parallel session usage detected. You typically work with one Claude Code session at a time.
        </p>
""" + "      ";

    static final String MULTI_CLAUDING_STATS = """

        <div style="display: flex; gap: 24px; margin: 12px 0;">
          <div style="text-align: center;">
            <div style="font-size: 24px; font-weight: 700; color: #7c3aed;">${overlap_events}</div>
            <div style="font-size: 11px; color: #64748b; text-transform: uppercase;">Overlap Events</div>
          </div>
          <div style="text-align: center;">
            <div style="font-size: 24px; font-weight: 700; color: #7c3aed;">${sessions_involved}</div>
            <div style="font-size: 11px; color: #64748b; text-transform: uppercase;">Sessions Involved</div>
          </div>
          <div style="text-align: center;">
            <div style="font-size: 24px; font-weight: 700; color: #7c3aed;">${pct_of_messages}%</div>
            <div style="font-size: 11px; color: #64748b; text-transform: uppercase;">Of Messages</div>
          </div>
        </div>
        <p style="font-size: 13px; color: #475569; margin-top: 12px;">
          You run multiple Claude Code sessions simultaneously. Multi-clauding is detected when sessions
          overlap in time, suggesting parallel workflows.
        </p>
""" + "      ";

// ------------------------------------------------------------------ CSS.

    static final String CSS = """

    * { box-sizing: border-box; margin: 0; padding: 0; }
    body { font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif; background: #f8fafc; color: #334155; line-height: 1.65; padding: 48px 24px; }
    .container { max-width: 800px; margin: 0 auto; }
    h1 { font-size: 32px; font-weight: 700; color: #0f172a; margin-bottom: 8px; }
    h2 { font-size: 20px; font-weight: 600; color: #0f172a; margin-top: 48px; margin-bottom: 16px; }
    .subtitle { color: #64748b; font-size: 15px; margin-bottom: 32px; }
    .nav-toc { display: flex; flex-wrap: wrap; gap: 8px; margin: 24px 0 32px 0; padding: 16px; background: white; border-radius: 8px; border: 1px solid #e2e8f0; }
    .nav-toc a { font-size: 12px; color: #64748b; text-decoration: none; padding: 6px 12px; border-radius: 6px; background: #f1f5f9; transition: all 0.15s; }
    .nav-toc a:hover { background: #e2e8f0; color: #334155; }
    .stats-row { display: flex; gap: 24px; margin-bottom: 40px; padding: 20px 0; border-top: 1px solid #e2e8f0; border-bottom: 1px solid #e2e8f0; flex-wrap: wrap; }
    .stat { text-align: center; }
    .stat-value { font-size: 24px; font-weight: 700; color: #0f172a; }
    .stat-label { font-size: 11px; color: #64748b; text-transform: uppercase; }
    .at-a-glance { background: linear-gradient(135deg, #fef3c7 0%, #fde68a 100%); border: 1px solid #f59e0b; border-radius: 12px; padding: 20px 24px; margin-bottom: 32px; }
    .glance-title { font-size: 16px; font-weight: 700; color: #92400e; margin-bottom: 16px; }
    .glance-sections { display: flex; flex-direction: column; gap: 12px; }
    .glance-section { font-size: 14px; color: #78350f; line-height: 1.6; }
    .glance-section strong { color: #92400e; }
    .see-more { color: #b45309; text-decoration: none; font-size: 13px; white-space: nowrap; }
    .see-more:hover { text-decoration: underline; }
    .project-areas { display: flex; flex-direction: column; gap: 12px; margin-bottom: 32px; }
    .project-area { background: white; border: 1px solid #e2e8f0; border-radius: 8px; padding: 16px; }
    .area-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
    .area-name { font-weight: 600; font-size: 15px; color: #0f172a; }
    .area-count { font-size: 12px; color: #64748b; background: #f1f5f9; padding: 2px 8px; border-radius: 4px; }
    .area-desc { font-size: 14px; color: #475569; line-height: 1.5; }
    .narrative { background: white; border: 1px solid #e2e8f0; border-radius: 8px; padding: 20px; margin-bottom: 24px; }
    .narrative p { margin-bottom: 12px; font-size: 14px; color: #475569; line-height: 1.7; }
    .key-insight { background: #f0fdf4; border: 1px solid #bbf7d0; border-radius: 8px; padding: 12px 16px; margin-top: 12px; font-size: 14px; color: #166534; }
    .section-intro { font-size: 14px; color: #64748b; margin-bottom: 16px; }
    .big-wins { display: flex; flex-direction: column; gap: 12px; margin-bottom: 24px; }
    .big-win { background: #f0fdf4; border: 1px solid #bbf7d0; border-radius: 8px; padding: 16px; }
    .big-win-title { font-weight: 600; font-size: 15px; color: #166534; margin-bottom: 8px; }
    .big-win-desc { font-size: 14px; color: #15803d; line-height: 1.5; }
    .friction-categories { display: flex; flex-direction: column; gap: 16px; margin-bottom: 24px; }
    .friction-category { background: #fef2f2; border: 1px solid #fca5a5; border-radius: 8px; padding: 16px; }
    .friction-title { font-weight: 600; font-size: 15px; color: #991b1b; margin-bottom: 6px; }
    .friction-desc { font-size: 13px; color: #7f1d1d; margin-bottom: 10px; }
    .friction-examples { margin: 0 0 0 20px; font-size: 13px; color: #334155; }
    .friction-examples li { margin-bottom: 4px; }
    .claude-md-section { background: #eff6ff; border: 1px solid #bfdbfe; border-radius: 8px; padding: 16px; margin-bottom: 20px; }
    .claude-md-section h3 { font-size: 14px; font-weight: 600; color: #1e40af; margin: 0 0 12px 0; }
    .claude-md-actions { margin-bottom: 12px; padding-bottom: 12px; border-bottom: 1px solid #dbeafe; }
    .copy-all-btn { background: #2563eb; color: white; border: none; border-radius: 4px; padding: 6px 12px; font-size: 12px; cursor: pointer; font-weight: 500; transition: all 0.2s; }
    .copy-all-btn:hover { background: #1d4ed8; }
    .copy-all-btn.copied { background: #16a34a; }
    .claude-md-item { display: flex; flex-wrap: wrap; align-items: flex-start; gap: 8px; padding: 10px 0; border-bottom: 1px solid #dbeafe; }
    .claude-md-item:last-child { border-bottom: none; }
    .cmd-checkbox { margin-top: 2px; }
    .cmd-code { background: white; padding: 8px 12px; border-radius: 4px; font-size: 12px; color: #1e40af; border: 1px solid #bfdbfe; font-family: monospace; display: block; white-space: pre-wrap; word-break: break-word; flex: 1; }
    .cmd-why { font-size: 12px; color: #64748b; width: 100%; padding-left: 24px; margin-top: 4px; }
    .features-section, .patterns-section { display: flex; flex-direction: column; gap: 12px; margin: 16px 0; }
    .feature-card { background: #f0fdf4; border: 1px solid #86efac; border-radius: 8px; padding: 16px; }
    .pattern-card { background: #f0f9ff; border: 1px solid #7dd3fc; border-radius: 8px; padding: 16px; }
    .feature-title, .pattern-title { font-weight: 600; font-size: 15px; color: #0f172a; margin-bottom: 6px; }
    .feature-oneliner { font-size: 14px; color: #475569; margin-bottom: 8px; }
    .pattern-summary { font-size: 14px; color: #475569; margin-bottom: 8px; }
    .feature-why, .pattern-detail { font-size: 13px; color: #334155; line-height: 1.5; }
    .feature-examples { margin-top: 12px; }
    .feature-example { padding: 8px 0; border-top: 1px solid #d1fae5; }
    .feature-example:first-child { border-top: none; }
    .example-desc { font-size: 13px; color: #334155; margin-bottom: 6px; }
    .example-code-row { display: flex; align-items: flex-start; gap: 8px; }
    .example-code { flex: 1; background: #f1f5f9; padding: 8px 12px; border-radius: 4px; font-family: monospace; font-size: 12px; color: #334155; overflow-x: auto; white-space: pre-wrap; }
    .copyable-prompt-section { margin-top: 12px; padding-top: 12px; border-top: 1px solid #e2e8f0; }
    .copyable-prompt-row { display: flex; align-items: flex-start; gap: 8px; }
    .copyable-prompt { flex: 1; background: #f8fafc; padding: 10px 12px; border-radius: 4px; font-family: monospace; font-size: 12px; color: #334155; border: 1px solid #e2e8f0; white-space: pre-wrap; line-height: 1.5; }
    .feature-code { background: #f8fafc; padding: 12px; border-radius: 6px; margin-top: 12px; border: 1px solid #e2e8f0; display: flex; align-items: flex-start; gap: 8px; }
    .feature-code code { flex: 1; font-family: monospace; font-size: 12px; color: #334155; white-space: pre-wrap; }
    .pattern-prompt { background: #f8fafc; padding: 12px; border-radius: 6px; margin-top: 12px; border: 1px solid #e2e8f0; }
    .pattern-prompt code { font-family: monospace; font-size: 12px; color: #334155; display: block; white-space: pre-wrap; margin-bottom: 8px; }
    .prompt-label { font-size: 11px; font-weight: 600; text-transform: uppercase; color: #64748b; margin-bottom: 6px; }
    .copy-btn { background: #e2e8f0; border: none; border-radius: 4px; padding: 4px 8px; font-size: 11px; cursor: pointer; color: #475569; flex-shrink: 0; }
    .copy-btn:hover { background: #cbd5e1; }
    .charts-row { display: grid; grid-template-columns: 1fr 1fr; gap: 24px; margin: 24px 0; }
    .chart-card { background: white; border: 1px solid #e2e8f0; border-radius: 8px; padding: 16px; }
    .chart-title { font-size: 12px; font-weight: 600; color: #64748b; text-transform: uppercase; margin-bottom: 12px; }
    .bar-row { display: flex; align-items: center; margin-bottom: 6px; }
    .bar-label { width: 100px; font-size: 11px; color: #475569; flex-shrink: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .bar-track { flex: 1; height: 6px; background: #f1f5f9; border-radius: 3px; margin: 0 8px; }
    .bar-fill { height: 100%; border-radius: 3px; }
    .bar-value { width: 28px; font-size: 11px; font-weight: 500; color: #64748b; text-align: right; }
    .empty { color: #94a3b8; font-size: 13px; }
    .horizon-section { display: flex; flex-direction: column; gap: 16px; }
    .horizon-card { background: linear-gradient(135deg, #faf5ff 0%, #f5f3ff 100%); border: 1px solid #c4b5fd; border-radius: 8px; padding: 16px; }
    .horizon-title { font-weight: 600; font-size: 15px; color: #5b21b6; margin-bottom: 8px; }
    .horizon-possible { font-size: 14px; color: #334155; margin-bottom: 10px; line-height: 1.5; }
    .horizon-tip { font-size: 13px; color: #6b21a8; background: rgba(255,255,255,0.6); padding: 8px 12px; border-radius: 4px; }
    .feedback-header { margin-top: 48px; color: #64748b; font-size: 16px; }
    .feedback-intro { font-size: 13px; color: #94a3b8; margin-bottom: 16px; }
    .feedback-section { margin-top: 16px; }
    .feedback-section h3 { font-size: 14px; font-weight: 600; color: #475569; margin-bottom: 12px; }
    .feedback-card { background: white; border: 1px solid #e2e8f0; border-radius: 8px; padding: 16px; margin-bottom: 12px; }
    .feedback-card.team-card { background: #eff6ff; border-color: #bfdbfe; }
    .feedback-card.model-card { background: #faf5ff; border-color: #e9d5ff; }
    .feedback-title { font-weight: 600; font-size: 14px; color: #0f172a; margin-bottom: 6px; }
    .feedback-detail { font-size: 13px; color: #475569; line-height: 1.5; }
    .feedback-evidence { font-size: 12px; color: #64748b; margin-top: 8px; }
    .fun-ending { background: linear-gradient(135deg, #fef3c7 0%, #fde68a 100%); border: 1px solid #fbbf24; border-radius: 12px; padding: 24px; margin-top: 40px; text-align: center; }
    .fun-headline { font-size: 18px; font-weight: 600; color: #78350f; margin-bottom: 8px; }
    .fun-detail { font-size: 14px; color: #92400e; }
    .collapsible-section { margin-top: 16px; }
    .collapsible-header { display: flex; align-items: center; gap: 8px; cursor: pointer; padding: 12px 0; border-bottom: 1px solid #e2e8f0; }
    .collapsible-header h3 { margin: 0; font-size: 14px; font-weight: 600; color: #475569; }
    .collapsible-arrow { font-size: 12px; color: #94a3b8; transition: transform 0.2s; }
    .collapsible-content { display: none; padding-top: 16px; }
    .collapsible-content.open { display: block; }
    .collapsible-header.open .collapsible-arrow { transform: rotate(90deg); }
    @media (max-width: 640px) { .charts-row { grid-template-columns: 1fr; } .stats-row { justify-content: center; } }
""" + "  ";

// ------------------------------------------------------------------ Inline JS.

    static final String JS = """

    function toggleCollapsible(header) {
      header.classList.toggle('open');
      const content = header.nextElementSibling;
      content.classList.toggle('open');
    }
    function copyText(btn) {
      const code = btn.previousElementSibling;
      navigator.clipboard.writeText(code.textContent).then(() => {
        btn.textContent = 'Copied!';
        setTimeout(() => { btn.textContent = 'Copy'; }, 2000);
      });
    }
    function copyCmdItem(idx) {
      const checkbox = document.getElementById('cmd-' + idx);
      if (checkbox) {
        const text = checkbox.dataset.text;
        navigator.clipboard.writeText(text).then(() => {
          const btn = checkbox.nextElementSibling.querySelector('.copy-btn');
          if (btn) { btn.textContent = 'Copied!'; setTimeout(() => { btn.textContent = 'Copy'; }, 2000); }
        });
      }
    }
    function copyAllCheckedClaudeMd() {
      const checkboxes = document.querySelectorAll('.cmd-checkbox:checked');
      const texts = [];
      checkboxes.forEach(cb => {
        if (cb.dataset.text) { texts.push(cb.dataset.text); }
      });
      const combined = texts.join('\\n');
      const btn = document.querySelector('.copy-all-btn');
      if (btn) {
        navigator.clipboard.writeText(combined).then(() => {
          btn.textContent = 'Copied ' + texts.length + ' items!';
          btn.classList.add('copied');
          setTimeout(() => { btn.textContent = 'Copy All Checked'; btn.classList.remove('copied'); }, 2000);
        });
      }
    }
    // Timezone selector for time of day chart (data is from our own analytics, not user input)
    const rawHourCounts = ${hour_counts_json};
    function updateHourHistogram(offsetFromPT) {
      const periods = [
        { label: "Morning (6-12)", range: [6,7,8,9,10,11] },
        { label: "Afternoon (12-18)", range: [12,13,14,15,16,17] },
        { label: "Evening (18-24)", range: [18,19,20,21,22,23] },
        { label: "Night (0-6)", range: [0,1,2,3,4,5] }
      ];
      const adjustedCounts = {};
      for (const [hour, count] of Object.entries(rawHourCounts)) {
        const newHour = (parseInt(hour) + offsetFromPT + 24) % 24;
        adjustedCounts[newHour] = (adjustedCounts[newHour] || 0) + count;
      }
      const periodCounts = periods.map(p => ({
        label: p.label,
        count: p.range.reduce((sum, h) => sum + (adjustedCounts[h] || 0), 0)
      }));
      const maxCount = Math.max(...periodCounts.map(p => p.count)) || 1;
      const container = document.getElementById('hour-histogram');
      container.textContent = '';
      periodCounts.forEach(p => {
        const row = document.createElement('div');
        row.className = 'bar-row';
        const label = document.createElement('div');
        label.className = 'bar-label';
        label.textContent = p.label;
        const track = document.createElement('div');
        track.className = 'bar-track';
        const fill = document.createElement('div');
        fill.className = 'bar-fill';
        fill.style.width = (p.count / maxCount) * 100 + '%';
        fill.style.background = '#8b5cf6';
        track.appendChild(fill);
        const value = document.createElement('div');
        value.className = 'bar-value';
        value.textContent = p.count;
        row.appendChild(label);
        row.appendChild(track);
        row.appendChild(value);
        container.appendChild(row);
      });
    }
    document.getElementById('timezone-select').addEventListener('change', function() {
      const customInput = document.getElementById('custom-offset');
      if (this.value === 'custom') {
        customInput.style.display = 'inline-block';
        customInput.focus();
      } else {
        customInput.style.display = 'none';
        updateHourHistogram(parseInt(this.value));
      }
    });
    document.getElementById('custom-offset').addEventListener('change', function() {
      const offset = parseInt(this.value) + 8;
      updateHourHistogram(offset);
    });
""" + "  ";

// ------------------------------------------------------------------ Page skeleton.

    static final String PAGE = """
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <title>Claude Code Insights</title>
  <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&display=swap" rel="stylesheet">
  <style>${css}</style>
</head>
<body>
  <div class="container">
    <h1>Claude Code Insights</h1>
    <p class="subtitle">${total_messages} messages across ${total_sessions} sessions${scanned_suffix} | ${date_start} to ${date_end}</p>

    ${at_a_glance}

    <nav class="nav-toc">
      <a href="#section-work">What You Work On</a>
      <a href="#section-usage">How You Use CC</a>
      <a href="#section-wins">Impressive Things</a>
      <a href="#section-friction">Where Things Go Wrong</a>
      <a href="#section-features">Features to Try</a>
      <a href="#section-patterns">New Usage Patterns</a>
      <a href="#section-horizon">On the Horizon</a>
      <a href="#section-feedback">Team Feedback</a>
    </nav>

    <div class="stats-row">
      <div class="stat"><div class="stat-value">${total_messages}</div><div class="stat-label">Messages</div></div>
      <div class="stat"><div class="stat-value">+${lines_added}/-${lines_removed}</div><div class="stat-label">Lines</div></div>
      <div class="stat"><div class="stat-value">${total_files_modified}</div><div class="stat-label">Files</div></div>
      <div class="stat"><div class="stat-value">${days_active}</div><div class="stat-label">Days</div></div>
      <div class="stat"><div class="stat-value">${messages_per_day}</div><div class="stat-label">Msgs/Day</div></div>
    </div>

    ${project_areas}

    <div class="charts-row">
      <div class="chart-card">
        <div class="chart-title">What You Wanted</div>
        ${goal_chart}
      </div>
      <div class="chart-card">
        <div class="chart-title">Top Tools Used</div>
        ${tools_chart}
      </div>
    </div>

    <div class="charts-row">
      <div class="chart-card">
        <div class="chart-title">Languages</div>
        ${languages_chart}
      </div>
      <div class="chart-card">
        <div class="chart-title">Session Types</div>
        ${session_types_chart}
      </div>
    </div>

    ${interaction}

    <!-- Response Time Distribution -->
    <div class="chart-card" style="margin: 24px 0;">
      <div class="chart-title">User Response Time Distribution</div>
      ${response_time_histogram}
      <div style="font-size: 12px; color: #64748b; margin-top: 8px;">
        Median: ${median_response_time}s &bull; Average: ${avg_response_time}s
      </div>
    </div>

    <!-- Multi-clauding Section (matching Python reference) -->
    <div class="chart-card" style="margin: 24px 0;">
      <div class="chart-title">Multi-Clauding (Parallel Sessions)</div>
      ${multi_clauding}
    </div>

    <!-- Time of Day & Tool Errors -->
    <div class="charts-row">
      <div class="chart-card">
        <div class="chart-title" style="display: flex; align-items: center; gap: 12px;">
          User Messages by Time of Day
          <select id="timezone-select" style="font-size: 12px; padding: 4px 8px; border-radius: 4px; border: 1px solid #e2e8f0;">
            <option value="0">PT (UTC-8)</option>
            <option value="3">ET (UTC-5)</option>
            <option value="8">London (UTC)</option>
            <option value="9">CET (UTC+1)</option>
            <option value="17">Tokyo (UTC+9)</option>
            <option value="custom">Custom offset...</option>
          </select>
          <input type="number" id="custom-offset" placeholder="UTC offset" style="display: none; width: 80px; font-size: 12px; padding: 4px; border-radius: 4px; border: 1px solid #e2e8f0;">
        </div>
        ${time_of_day_chart}
      </div>
      <div class="chart-card">
        <div class="chart-title">Tool Errors Encountered</div>
        ${tool_errors_chart}
      </div>
    </div>

    ${what_works}

    <div class="charts-row">
      <div class="chart-card">
        <div class="chart-title">What Helped Most (Claude's Capabilities)</div>
        ${success_chart}
      </div>
      <div class="chart-card">
        <div class="chart-title">Outcomes</div>
        ${outcomes_chart}
      </div>
    </div>

    ${friction}

    <div class="charts-row">
      <div class="chart-card">
        <div class="chart-title">Primary Friction Types</div>
        ${friction_chart}
      </div>
      <div class="chart-card">
        <div class="chart-title">Inferred Satisfaction (model-estimated)</div>
        ${satisfaction_chart}
      </div>
    </div>

    ${suggestions}

    ${horizon}

    ${fun_ending}

    ${team_feedback}
  </div>
  <script>${js}</script>
</body>
</html>""";
}
