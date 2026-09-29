---
name: adding-a-skill
description: Creates, updates, and validates portable agent skills inside /docs/skills/. Use when the user asks to add a new skill, create a skill, expand agent capabilities, or self-evolve workflows.
---

## Prerequisites
- Directory structure: `/docs/skills/skill-name.md` or `/docs/skills/skill-name/SKILL.md` (when additional files are needed).
- Compliance: The file must contain YAML frontmatter followed by Markdown content.
- The Skill must support progressive discovery: provide clear scoping upfront to allow the agent to understand its purpose without reading the entire file.
- The rest of the skill can contain sections for:  Step-by-step instructions,  Examples of inputs and outputs, Common edge cases - or other sections as needed.
- The skills file should be short (<100 lines) - use additional resource files or scripts if needed.


## Trigger Assessment
Evaluate if a new skill is necessary using these absolute rules:
1. **The non-trivial Rule:** A skill should be included where the correct solution cannot easily be found in normal best practices or the existing documentation.
2. **The non-unique Rule:** Skills are for tasks that recur. Do not create a skill for a one-off task.

## Instructions
1. **Skill-name and Filename:**
    - Choose a skill name which describes what the agent will do.
    - If standalone, use `/docs/skills/skill-name.md`. If extra assets/scripts are required, use `/docs/skills/skill-name/SKILL.md`.
    - Use lowercase, hyphenated names (`a-z`, `0-9`, `-`) without spaces or uppercase letters.

2. **Draft Frontmatter:**
    - Set `name` to match the lowercase skill name exactly.
    - Set `description` (max 1024 chars), explicitly including "Use when..." trigger keywords for discovery.

3. **Write Body Instructions:**
    - Write concise, imperative instructions using clear headings and lists.
    - Offload complex background information or scripts to separate files if approaching the 100-line limit.

4. **Self-Verification:**
    - Ensure frontmatter contains valid YAML syntax and no unescaped `<` or `>` brackets.