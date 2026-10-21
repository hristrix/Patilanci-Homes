package net.patilanci.homes;

import com.mojang.brigadier.arguments.StringArgumentType;

/** Home storage keys may contain Base64 padding, slashes, or legacy text. */
final class HomeCommands {
    private HomeCommands() {}

    static StringArgumentType slotArgument() {
        return StringArgumentType.string();
    }

    static String command(String action, String key) {
        return "/phome " + action + " " + StringArgumentType.escapeIfRequired(key);
    }
}
