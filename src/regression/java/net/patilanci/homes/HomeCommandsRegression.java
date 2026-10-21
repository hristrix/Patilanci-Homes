package net.patilanci.homes;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/** Runs without starting Minecraft; exercises the real dialog command encoding. */
public final class HomeCommandsRegression {
    public static void main(String[] args) throws Exception {
        CommandDispatcher<String> dispatcher = new CommandDispatcher<>();
        LiteralArgumentBuilder<String> root = LiteralArgumentBuilder.literal("phome");
        List<String> actions = List.of("detail", "tp", "rename", "update", "delete", "confirmdelete", "renamesave");
        for (String action : actions) {
            var slot = RequiredArgumentBuilder.<String, String>argument("slot", HomeCommands.slotArgument());
            if (action.equals("renamesave")) {
                slot.then(RequiredArgumentBuilder.<String, String>argument("name", StringArgumentType.greedyString())
                    .executes(c -> {
                        require(c.getSource().equals(StringArgumentType.getString(c,"slot")), "Rename changed home ID");
                        require(StringArgumentType.getString(c,"name").equals("Nether base 2"), "Rename lost name");
                        return 1;
                    }));
            } else {
                slot.executes(c -> {
                    require(c.getSource().equals(StringArgumentType.getString(c,"slot")), "Action changed home ID");
                    return 1;
                });
            }
            root.then(LiteralArgumentBuilder.<String>literal(action).then(slot));
        }
        dispatcher.register(root);
        int count = 0;
        for (int i = 1; i < 1000; i++) {
            String key = Base64.getEncoder().encodeToString(("Home " + i).getBytes(StandardCharsets.UTF_8));
            for (String action : actions) { check(dispatcher, action, key); count++; }
        }
        // Existing imports can have arbitrary keys. Keep them intact through commands.
        for (String key : List.of("ab/c+==", "legacy home", "quoted\"home", "back\\slash", "the_end", "minecraft:the_nether")) {
            for (String action : actions) { check(dispatcher, action, key); count++; }
        }
        System.out.println("Passed " + count + " home dialog command round trips.");
    }

    private static void check(CommandDispatcher<String> dispatcher, String action, String key) throws Exception {
        String command = HomeCommands.command(action, key).substring(1);
        if (action.equals("renamesave")) command += " Nether base 2";
        require(dispatcher.execute(command, key) == 1, "Command failed: " + command);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
