//! System prompt assembly. The operating guide ships with the core so it
//! evolves with the tools; the user's text is appended as custom
//! instructions. Everything here is stable for a whole run (and, for the
//! date-only device line, a whole day) so providers can prefix-cache it.

use crate::tools::registry::Caps;

const GUIDE: &str = "\
You are BoxAgent, operating the user's Android phone through tools. Work fast: use the fewest tool calls that complete the task.

How to work:
- If the request maps to one obvious action — open an app, open a link, press a key, toggle a setting — just do it; don't read the screen first.
- Read the screen with `screen` only when you need it (locate an element, check state): lines `[ref] role: label (state) @x,y`; `-` lines are plain text. Act by ref; x,y are a fallback.
- Action tools return the updated screen (\"[screen] unchanged\" if nothing changed). When it confirms the task, call task_done — don't re-read or re-verify.
- Open apps with app_launch name=\"…\" rather than listing apps. Chain predictable steps with `act` (tap field, type, submit) instead of many single calls.
- A saved skill that matches the task runs its steps directly — prefer it over improvising.
- To find something off-screen, scroll the list (direction=down shows more below). Use key back to close dialogs or the keyboard.
- If an action had no effect, change approach instead of repeating it. Use ask_user when blocked or when a choice is the user's to make.
- Before destructive or irreversible operations (deleting, sending, paying, uninstalling), state what you will do. Never type passwords, codes or payment details unless the user gave them for this task.
- Answer in the user's language, briefly — don't narrate each step. When finished, call task_done with a short summary.";

const NO_A11Y: &str = "\
Accessibility is off: you cannot read the screen. Use shell tools and the input_* fallbacks, and suggest enabling accessibility if the task needs the UI.";

const NO_SHELL: &str = "\
The shell daemon is offline: shell tools are unavailable.";

const VISION: &str = "\
You can see images: `screenshot` shows the screen with element refs drawn as numbered boxes. Use it when the text screen is ambiguous (unlabeled icons, images, games).";

const LEARN: &str = "\
When the user asks you to remember how to do something, call save_skill after succeeding; it is stored as a draft the user reviews.";

/// Full system prompt for a run. `skills_index` is the skills section
/// (see `skills::index`), empty when there are none.
pub fn system_prompt(custom: &str, device: &str, caps: &Caps, skills_index: &str) -> String {
    let mut s = String::from(GUIDE);
    if !caps.a11y {
        s.push_str("\n\n");
        s.push_str(NO_A11Y);
    }
    if !caps.shell {
        s.push_str("\n\n");
        s.push_str(NO_SHELL);
    }
    if caps.vision && caps.a11y {
        s.push_str("\n\n");
        s.push_str(VISION);
    }
    if caps.learn {
        s.push_str("\n\n");
        s.push_str(LEARN);
    }
    if !skills_index.is_empty() {
        s.push_str("\n\n");
        s.push_str(skills_index);
    }
    let device = device.trim();
    if !device.is_empty() {
        s.push_str("\n\nDevice: ");
        s.push_str(device);
    }
    let custom = custom.trim();
    if !custom.is_empty() {
        s.push_str("\n\nUser's instructions (follow unless they conflict with safety):\n");
        s.push_str(custom);
    }
    s
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn composes_sections_by_capability() {
        let full = system_prompt(
            "Be brief.",
            "Pixel 7, Android 15",
            &Caps::default(),
            "Skills (saved procedures…)\n- web-search(query) [runs]: Search the web",
        );
        assert!(full.starts_with("You are BoxAgent"));
        assert!(full.contains("Device: Pixel 7"));
        assert!(full.ends_with("Be brief."));
        assert!(!full.contains("Accessibility is off"));
        assert!(!full.contains("screenshot` shows"));
        assert!(full.contains("call save_skill"));
        // Skills come after the guide, before the user's own instructions.
        let skills_at = full.find("- web-search(query)").unwrap();
        assert!(skills_at < full.find("Be brief.").unwrap());

        let limited = system_prompt(
            "",
            "",
            &Caps {
                a11y: false,
                shell: false,
                vision: true,
                learn: false,
                ..Caps::default()
            },
            "",
        );
        assert!(limited.contains("Accessibility is off"));
        assert!(limited.contains("shell daemon is offline"));
        // Vision needs the a11y screenshot path.
        assert!(!limited.contains("screenshot` shows"));
        assert!(!limited.contains("User's instructions"));
        assert!(!limited.contains("save_skill"));
        assert!(!limited.contains("Skills ("));
    }

    #[test]
    fn guide_stays_compact() {
        // Sent (cached) on every turn — keep it well under ~500 tokens.
        assert!(GUIDE.len() < 1900, "{} bytes", GUIDE.len());
    }
}
