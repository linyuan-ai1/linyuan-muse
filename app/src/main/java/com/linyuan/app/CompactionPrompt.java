package com.linyuan.app;

public class CompactionPrompt {

    public static String systemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("Your task is to compress a conversation into a structured summary so the work can continue seamlessly.\n");
        sb.append("Key point: this summary will be used by **another AI** (a future you taking over) as its only memory, ");
        sb.append("so optimize for \"an AI can keep working from this\", not for \"pleasant human reading\".\n\n");

        sb.append("[Analysis steps]\n");
        sb.append("Walk through these in your head first (do not output this part):\n");
        sb.append("1. What did the user originally ask for? (keep the exact words)\n");
        sb.append("2. What succeeded, what failed, and why?\n");
        sb.append("3. What did the user correct along the way? (this kind of information matters most)\n");
        sb.append("4. What was being done last?\n");
        sb.append("5. What is still unfinished?\n");
        sb.append("6. Which concrete details (names, numbers, paths, conclusions) must be preserved verbatim?\n\n");

        sb.append("[Output structure] Output strictly in the following sections:\n\n");
        sb.append("## User intent\n");
        sb.append("The user's original request and later additions; quote key requirements verbatim. If the goal changed midway, write out how it changed.\n\n");
        sb.append("## Completed\n");
        sb.append("What has been done, concretely: what was done, what the result was, and which key identifiers are involved (names/IDs/values).\n\n");
        sb.append("## Errors & corrections\n");
        sb.append("- Problems encountered and how they were solved\n");
        sb.append("- Approaches that were tried and **did not work** (so they are not repeated)\n");
        sb.append("- The user's corrections (keep the exact words) - these represent preferences already learned\n\n");
        sb.append("## In progress\n");
        sb.append("What was underway at the end; state clearly which step it stopped at.\n\n");
        sb.append("## To do\n");
        sb.append("Things the user asked for that have not started. Distinguish \"explicitly requested\" from \"inferred\".\n\n");
        sb.append("## Key information\n");
        sb.append("Concrete information needed to continue the work: names, numbers, dates, amounts, conclusions, constraints, preferences.\n\n");

        sb.append("[Must be preserved verbatim]\n");
        sb.append("- The user's preferences, identity and goals (exact words)\n");
        sb.append("- Concrete numbers, dates, amounts, version numbers, IDs\n");
        sb.append("- Person names, brand names, product names, place names, proper nouns\n");
        sb.append("- Things the user explicitly said, like \"do not do X\", \"what I actually want is Y\", \"this is wrong, because...\"\n");
        sb.append("- Unfinished to-dos and conclusions already reached\n");
        sb.append("- Code, command lines, file paths, original error messages (**do not translate or rewrite them**)\n\n");

        sb.append("[Must be discarded]\n");
        sb.append("- Pleasantries, small talk, contentless acknowledgements (\"OK\", \"got it\", \"happy to help\")\n");
        sb.append("- Repeated statements, details of the trial-and-error process\n");
        sb.append("- Content the system injects separately (e.g. the system prompt itself)\n\n");

        sb.append("[Hard requirements]\n");
        sb.append("1. **Never fabricate**: rely only on the given conversation; do not add information that is not in it.\n");
        sb.append("2. **Never change the original meaning**: especially for the user's words - keeping them verbatim is safer than rewriting.\n");
        sb.append("3. **Output in the language of the conversation being compressed** (a Chinese conversation -> output in Chinese); do not translate.\n");
        sb.append("4. Chinese person names, brands, product names, amounts, dates and version numbers must be **kept verbatim**; do not convert or rewrite them.\n");
        sb.append("5. Be concise but complete: rather keep one extra fact than drop information that would prevent duplicated work.\n");
        sb.append("6. Output only the six sections above; no extra opening or closing remarks.");
        return sb.toString();
    }

    public static String mergeInstruction(String priorSummary) {
        if (priorSummary == null || priorSummary.trim().isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n[Existing summary (a digest of earlier content; this run must merge into and update it)]\n");
        sb.append(priorSummary.trim()).append("\n\n");
        sb.append("Merge requirements:\n");
        sb.append("- Carry over everything from the old summary that is **still valid** (goals, constraints, user preferences, conclusions); do not lose it;\n");
        sb.append("- The new conversation is more recent than the old summary: where they conflict, the **new conversation** wins - state the corrected content;\n");
        sb.append("- Move finished items from \"In progress\" to \"Completed\"; update the status of blockers that have been resolved;\n");
        sb.append("- Information that has been thoroughly overruled or is no longer needed may be dropped.");
        return sb.toString();
    }

    public static String summaryPrefix() {
        return "[Below is a compressed summary of earlier conversation (generated by the system, not the user's words; "
                + "parts that were the user's own words have been kept verbatim)]\n\n";
    }
}
