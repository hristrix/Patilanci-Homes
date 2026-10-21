package net.patilanci.homes;

import com.google.gson.*;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.dialog.AfterAction;
import net.minecraft.dialog.DialogActionButtonData;
import net.minecraft.dialog.DialogButtonData;
import net.minecraft.dialog.DialogCommonData;
import net.minecraft.dialog.action.SimpleDialogAction;
import net.minecraft.dialog.action.DynamicRunCommandDialogAction;
import net.minecraft.dialog.action.ParsedTemplate;
import net.minecraft.dialog.input.TextInputControl;
import net.minecraft.dialog.type.DialogInput;
import net.minecraft.dialog.body.DialogBody;
import com.mojang.serialization.JsonOps;
import net.minecraft.dialog.body.PlainMessageDialogBody;
import net.minecraft.dialog.type.MultiActionDialog;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.entity.EntityPose;
import org.yaml.snakeyaml.Yaml;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class PatilanciHomes implements ModInitializer {
    private static final Path DIR = Path.of("config", "patilanci-homes");
    private static final Path DB = DIR.resolve("homes.json");
    private static final Path CONFIG = DIR.resolve("config.yml");
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Map<String, LinkedHashMap<String, Home>> homes = new LinkedHashMap<>();
    private volatile Settings settings = Settings.defaults();
    private final Map<UUID, Long> lastTeleport = new HashMap<>();
    private final Map<UUID, Long> lastCreate = new HashMap<>();
    private final Map<UUID, Long> pendingTeleport = new HashMap<>();
    private record Settings(int defaultLimit, int operatorLimit, int maxNameLength, boolean allowNether, boolean allowEnd, boolean allowOverwrite, int columns, String title, String detailTitle, String deleteTitle, String prefix, Map<String,String> messages, Map<String,String> buttons, Map<String,Integer> playerLimits, int teleportCooldown, int createCooldown, int warmupSeconds, boolean cancelOnMove, boolean forbidCrossDimension, boolean safeTeleport, boolean allowDelete, boolean allowRename, boolean allowUpdate, boolean showCoordinates, boolean showWorld, boolean showCreate, boolean showTeleport, boolean showUpdate, boolean showRename, boolean showDelete, Set<String> blockedWorlds, Map<String,String> tooltips) {
        static Settings defaults() {
            return new Settings(5, -1, 24, true, true, false, 2, "Patilanci • Homes", "Home • {name}", "Delete {name}?", "§6[Homes] §f",
                Map.ofEntries(Map.entry("created", "Created home: {name}"), Map.entry("deleted", "Deleted {name}"), Map.entry("renamed", "Renamed home to {name}"), Map.entry("updated", "Updated {name}"), Map.entry("teleported", "Teleported to {name}"), Map.entry("not-found", "Home not found."), Map.entry("limit", "Home limit reached ({limit})."), Map.entry("duplicate", "This name is already in use."), Map.entry("invalid-name", "Home names must be 1-{max} characters."), Map.entry("reload", "Configuration reloaded successfully."), Map.entry("reload-failed", "Reload failed; previous settings remain active. Check server logs."), Map.entry("restricted-world", "You cannot save homes in this dimension."), Map.entry("read-only", "Storage is read-only to prevent data loss. Check server logs."), Map.entry("cooldown", "Please wait {seconds} seconds."), Map.entry("disabled", "This action is disabled."), Map.entry("unsafe", "The saved location is unsafe."), Map.entry("cross-dimension", "Teleports across dimensions are disabled."), Map.entry("warmup", "Teleporting in {seconds} seconds. Stay still."), Map.entry("moved", "Teleport cancelled because you moved.")),
                Map.of("create", "+ Create home", "teleport", "➤ Teleport", "update", "↻ Update location", "rename", "✎ Rename", "delete", "✕ Delete", "back", "← Back", "confirm", "Delete home", "cancel", "Cancel"), Map.of(), 0, 0, 0, true, false, true, true, true, true, true, true, true, true, true, true, true, Set.of(), Map.of("home", "Open saved home", "create", "Save current location", "teleport", "Teleport to saved position", "update", "Move home to your current location", "rename", "Rename with /renamehome", "delete", "Remove home", "back", "Return to homes", "confirm", "Confirm permanent deletion", "cancel", "Return without deleting"));
        }
    }
    private boolean readOnly = false;
    private record Home(String slot, String name, String world, double x, double y, double z, float yaw, float pitch) {}
    private final Map<UUID, String> pendingDeletion = new HashMap<>();

    @Override public void onInitialize() {
        Warmups.initialize();
        try { Files.createDirectories(DIR); try { reloadSettings(); } catch (Exception configError) { System.err.println("[PatilanciHomes] Config invalid; using defaults until /homes reload: " + configError); } load(); importLegacyIfEmpty(); }
        catch (Exception e) { readOnly = true; System.err.println("[PatilanciHomes] Storage safety mode: " + e); }
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> {
            dispatcher.register(literal("homes").executes(c -> { menu(c.getSource().getPlayerOrThrow()); return 1; })
                .then(literal("reload").requires(source -> source.hasPermissionLevel(2)).executes(c -> {
                    try { reloadSettings(); c.getSource().sendFeedback(() -> Text.literal("[Homes] Config reloaded."), false); return 1; }
                    catch (Exception ex) { System.err.println("[PatilanciHomes] Reload rejected: " + ex); c.getSource().sendError(Text.literal("[Homes] Invalid config. Existing settings unchanged. See console.")); return 0; }
                })));
            dispatcher.register(literal("sethome").executes(c -> create(c.getSource().getPlayerOrThrow(), "Home " + (list(c.getSource().getPlayerOrThrow()).size() + 1)))
                .then(argument("name", StringArgumentType.greedyString()).executes(c -> create(c.getSource().getPlayerOrThrow(), StringArgumentType.getString(c,"name")))));
            dispatcher.register(literal("home").executes(c -> { menu(c.getSource().getPlayerOrThrow()); return 1; })
                .then(argument("name", StringArgumentType.greedyString()).executes(c -> teleportName(c.getSource().getPlayerOrThrow(), StringArgumentType.getString(c,"name")))));
            dispatcher.register(literal("delhome").then(argument("name", StringArgumentType.greedyString()).executes(c -> {ServerPlayerEntity p=c.getSource().getPlayerOrThrow(); String key=keyByName(p,StringArgumentType.getString(c,"name"));if(key==null){msg(p,tr("not-found"));return 0;} confirmDelete(p,key);return 1;})));
            dispatcher.register(literal("renamehome").then(argument("old", StringArgumentType.word()).then(argument("new",StringArgumentType.greedyString()).executes(c -> renameName(c.getSource().getPlayerOrThrow(),StringArgumentType.getString(c,"old"),StringArgumentType.getString(c,"new"))))));
            dispatcher.register(literal("updatehome").then(argument("name",StringArgumentType.greedyString()).executes(c -> updateName(c.getSource().getPlayerOrThrow(),StringArgumentType.getString(c,"name")))));
            dispatcher.register(literal("phome").then(literal("rename").then(argument("slot",HomeCommands.slotArgument()).executes(c->{renameDialog(c.getSource().getPlayerOrThrow(),StringArgumentType.getString(c,"slot"));return 1;})))
                .then(literal("renamesave").then(argument("slot",HomeCommands.slotArgument()).then(argument("name",StringArgumentType.greedyString()).executes(c->renameSlot(c.getSource().getPlayerOrThrow(),StringArgumentType.getString(c,"slot"),StringArgumentType.getString(c,"name"))))))
                .then(literal("open").executes(c -> {menu(c.getSource().getPlayerOrThrow());return 1;}))
                .then(literal("detail").then(argument("slot",HomeCommands.slotArgument()).executes(c -> { detail(c.getSource().getPlayerOrThrow(), StringArgumentType.getString(c,"slot")); return 1;})))
                .then(literal("tp").then(argument("slot",HomeCommands.slotArgument()).executes(c -> teleport(c.getSource().getPlayerOrThrow(),StringArgumentType.getString(c,"slot")))))
                .then(literal("delete").then(argument("slot",HomeCommands.slotArgument()).executes(c -> {confirmDelete(c.getSource().getPlayerOrThrow(),StringArgumentType.getString(c,"slot"));return 1;})))
                .then(literal("confirmdelete").then(argument("slot",HomeCommands.slotArgument()).executes(c -> deleteSlot(c.getSource().getPlayerOrThrow(),StringArgumentType.getString(c,"slot")))))
                .then(literal("update").then(argument("slot",HomeCommands.slotArgument()).executes(c -> updateSlot(c.getSource().getPlayerOrThrow(),StringArgumentType.getString(c,"slot"))))));
        });
    }
    private LinkedHashMap<String, Home> list(ServerPlayerEntity p) { return homes.computeIfAbsent(p.getUuidAsString(), x -> new LinkedHashMap<>()); }
    private void msg(ServerPlayerEntity p, String s) { p.sendMessage(Text.literal(settings.prefix()+s), false); }
    private String tr(String key, String... args) {
        String value = settings.messages().getOrDefault(key, key);
        for (int i=0; i+1<args.length; i+=2) value = value.replace("{"+args[i]+"}", args[i+1]);
        return value;
    }
    private int limit(ServerPlayerEntity p) {
        Integer custom = settings.playerLimits().get(p.getGameProfile().name().toLowerCase(Locale.ROOT));
        return p.hasPermissionLevel(2) ? settings.operatorLimit() : (custom != null ? custom : settings.defaultLimit());
    }
    private boolean dimensionAllowed(ServerPlayerEntity p) {
        String world = p.getEntityWorld().getRegistryKey().getValue().toString();
        return !settings.blockedWorlds().contains(world) && (!world.equals("minecraft:the_nether") || settings.allowNether()) && (!world.equals("minecraft:the_end") || settings.allowEnd());
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> asMap(Object v, String section) {
        if (!(v instanceof Map<?,?> raw)) throw new IllegalArgumentException("Expected YAML section: " + section);
        Map<String,Object> map = new LinkedHashMap<>();
        raw.forEach((k,val) -> map.put(String.valueOf(k), val));
        return map;
    }
    private static int number(Map<String,Object> map, String key, int fallback, int min, int max) {
        Object v=map.getOrDefault(key,fallback); int n;
        try { n=Integer.parseInt(String.valueOf(v)); } catch(Exception e){throw new IllegalArgumentException("Invalid integer: "+key);}
        if(n<min||n>max) throw new IllegalArgumentException("Out of range: "+key);
        return n;
    }
    private static boolean bool(Map<String,Object> map, String key, boolean fallback) {
        String val=String.valueOf(map.getOrDefault(key,fallback));
        if(!val.equals("true")&&!val.equals("false"))throw new IllegalArgumentException("Invalid boolean: "+key);
        return Boolean.parseBoolean(val);
    }
    private static String str(Map<String,Object> map,String key,String fallback) {return String.valueOf(map.getOrDefault(key,fallback));}
    private synchronized void reloadSettings() throws IOException {
        if (!Files.exists(CONFIG)) Files.writeString(CONFIG, DEFAULT_YAML, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Object raw;
        try(Reader reader=Files.newBufferedReader(CONFIG)){raw=new Yaml().load(reader);}
        Map<String,Object> data=asMap(raw,"root"), limits=asMap(data.get("limits"),"limits"), ui=asMap(data.get("ui"),"ui"), worlds=asMap(data.get("worlds"),"worlds");
        Map<String,Object> messages=asMap(data.get("messages"),"messages"), buttons=asMap(data.get("buttons"),"buttons");
        Map<String,Object> teleport=asMap(data.getOrDefault("teleport",Map.of()),"teleport");
        Map<String,Object> cooldowns=asMap(data.getOrDefault("cooldowns",Map.of()),"cooldowns");
        Map<String,Object> actions=asMap(data.getOrDefault("actions",Map.of()),"actions");
        Map<String,Object> visibility=asMap(ui.getOrDefault("visibility",Map.of()),"ui.visibility");
        Map<String,Object> tooltipConfig=asMap(data.getOrDefault("tooltips",Map.of()),"tooltips");
        Set<String> blocked = new HashSet<>();
        Object blockedRaw = worlds.getOrDefault("blocked",List.of());
        if (!(blockedRaw instanceof List<?> blockedList)) throw new IllegalArgumentException("worlds.blocked must be a list");
        for (Object item:blockedList) {String world=String.valueOf(item);if(Identifier.tryParse(world)==null) throw new IllegalArgumentException("Invalid world: "+world);blocked.add(world);}
        Map<String,String> tt = new LinkedHashMap<>(Settings.defaults().tooltips());
        tooltipConfig.forEach((k,v)->tt.put(k,String.valueOf(v)));
        Map<String,Object> overrides=asMap(limits.getOrDefault("players",Map.of()),"limits.players");
        Map<String,Integer> custom=new LinkedHashMap<>();
        for(var e:overrides.entrySet()){
            if(!e.getKey().matches("[A-Za-z0-9_]{3,16}"))throw new IllegalArgumentException("Invalid username override: "+e.getKey());
            custom.put(e.getKey().toLowerCase(Locale.ROOT),number(overrides,e.getKey(),0,0,1000));
        }
        Settings d=Settings.defaults();
        Map<String,String> ms=new LinkedHashMap<>(d.messages()), bs=new LinkedHashMap<>(d.buttons());
        messages.forEach((k,v)->ms.put(k,String.valueOf(v)));
        buttons.forEach((k,v)->bs.put(k,String.valueOf(v)));
        Settings candidate=new Settings(number(limits,"normal",5,0,1000),number(limits,"operators",-1,-1,1000),number(data,"max-name-length",24,1,64),bool(worlds,"nether",true),bool(worlds,"end",true),bool(data,"overwrite-existing",false),number(ui,"columns",2,1,4),str(ui,"title",d.title()),str(ui,"detail-title",d.detailTitle()),str(ui,"delete-title",d.deleteTitle()),str(ui,"prefix",d.prefix()),Map.copyOf(ms),Map.copyOf(bs),Map.copyOf(custom),
            number(cooldowns,"teleport-seconds",0,0,86400),number(cooldowns,"create-seconds",0,0,86400),
            number(teleport,"warmup-seconds",0,0,60),bool(teleport,"cancel-on-move",true),
            bool(teleport,"block-cross-dimension",false),bool(teleport,"safe-location-check",true),
            bool(actions,"delete",true),bool(actions,"rename",true),bool(actions,"update",true),
            bool(ui,"show-coordinates",true),bool(ui,"show-world",true),
            bool(visibility,"create",true),bool(visibility,"teleport",true),bool(visibility,"update",true),
            bool(visibility,"rename",true),bool(visibility,"delete",true),Set.copyOf(blocked),Map.copyOf(tt));
        settings=candidate;
    }
    private static final String DEFAULT_YAML = """
        # /homes reload applies changes immediately. Only operators (permission level 2+) can reload.
        limits:
          normal: 5
          # -1 means unlimited. Operators always have unlimited homes.
          operators: -1
          # Optional player-specific limits, based on Minecraft username:
          players: {}
          # Example:
          # players:
          #   Steve: 10
        max-name-length: 24
        overwrite-existing: false
        worlds:
          nether: true
          end: true
          # Dimension IDs to disallow saving in:
          blocked: []
        cooldowns:
          create-seconds: 0
          teleport-seconds: 0
        teleport:
          block-cross-dimension: false
          safe-location-check: true
          # 0 = instant; pending teleports cancel if you move
          warmup-seconds: 0
          cancel-on-move: true
        actions:
          delete: true
          rename: true
          update: true
        ui:
          title: 'Patilanci • Homes'
          detail-title: 'Home • {name}'
          delete-title: 'Delete {name}?'
          prefix: '§6[Homes] §f'
          columns: 2
          show-coordinates: true
          show-world: true
          visibility:
            create: true
            teleport: true
            update: true
            rename: true
            delete: true
        tooltips:
          home: 'Open saved home'
          create: 'Save current location'
          teleport: 'Teleport to saved position'
          update: 'Move home to your current location'
          rename: 'Rename with /renamehome'
          delete: 'Remove home'
          back: 'Return to homes'
          confirm: 'Confirm permanent deletion'
          cancel: 'Return without deleting'
        buttons:
          create: '+ Create home'
          teleport: '➤ Teleport'
          update: '↻ Update location'
          rename: '✎ Rename'
          delete: '✕ Delete'
          back: '← Back'
          confirm: 'Delete home'
          cancel: 'Cancel'
        messages:
          created: 'Created home: {name}'
          deleted: 'Deleted {name}'
          renamed: 'Renamed home to {name}'
          updated: 'Updated {name}'
          teleported: 'Teleported to {name}'
          not-found: 'Home not found.'
          limit: 'Home limit reached ({limit}).'
          duplicate: 'This name is already in use.'
          invalid-name: 'Home names must be 1-{max} characters.'
          reload: 'Configuration reloaded successfully.'
          reload-failed: 'Reload failed; previous settings remain active.'
          restricted-world: 'You cannot save homes in this dimension.'
          read-only: 'Storage is read-only to prevent data loss. Check server logs.'
          cooldown: 'Please wait {seconds} seconds.'
          disabled: 'This action is disabled.'
          unsafe: 'The saved location is unsafe.'
          cross-dimension: 'Teleports across dimensions are disabled.'
          warmup: 'Teleporting in {seconds} seconds. Stay still.'
          moved: 'Teleport cancelled because you moved.'
        """;
    private void load() throws IOException {
        if (!Files.exists(DB)) return;
        JsonObject root = JsonParser.parseString(Files.readString(DB)).getAsJsonObject();
        if (!root.has("players") || !root.get("players").isJsonObject()) throw new IOException("Unexpected homes.json schema");
        for (var player : root.getAsJsonObject("players").entrySet()) {
            LinkedHashMap<String, Home> entries = new LinkedHashMap<>();
            for (var home : player.getValue().getAsJsonObject().entrySet()) {
                Home h = gson.fromJson(home.getValue(), Home.class);
                if (h != null) entries.put(home.getKey(), h);
            }
            homes.put(player.getKey(), entries);
        }
    }
    private boolean save() {
        if (readOnly) return false;
        try {
            Files.createDirectories(DIR);
            JsonObject root = new JsonObject(); root.addProperty("schema", 1);
            JsonObject players = new JsonObject();
            for (var player : homes.entrySet()) {
                JsonObject data = new JsonObject();
                for (var home : player.getValue().entrySet()) data.add(home.getKey(), gson.toJsonTree(home.getValue()));
                players.add(player.getKey(), data);
            }
            root.add("players",players);
            Path temp = DIR.resolve("homes.json.tmp");
            Files.writeString(temp, gson.toJson(root),StandardCharsets.UTF_8);
            if (Files.exists(DB)) Files.copy(DB, DIR.resolve("homes.json.bak"), StandardCopyOption.REPLACE_EXISTING);
            try { Files.move(temp,DB,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) {Files.move(temp,DB,StandardCopyOption.REPLACE_EXISTING);}
            return true;
        } catch (Exception e) {readOnly = true;System.err.println("[PatilanciHomes] Saving stopped to protect data: "+e);return false;}
    }
    @SuppressWarnings("unchecked") private void importLegacyIfEmpty() throws IOException {
        if (Files.exists(DB) || !homes.isEmpty()) return;
        List<Path> candidates = List.of(Path.of("plugins/ModexaHomes/homes.yml"),Path.of("plugins/DynamicDialogsHomes/homes.yml"),Path.of("plugins/DialogHomes/homes.yml"),Path.of("config/patilanci-homes/legacy-homes.yml"));
        for (Path candidate : candidates) {
            if (!Files.exists(candidate)) continue;
            Object parsed;
            try (Reader reader=Files.newBufferedReader(candidate)) {parsed=new Yaml().load(reader);}
            if (!(parsed instanceof Map<?,?> root) || !(root.get("homes") instanceof Map<?,?> all)) throw new IOException("Invalid legacy YAML; will not overwrite it");
            int count=0;
            for (var owner : all.entrySet()) {
                String uuid=String.valueOf(owner.getKey());
                try { UUID.fromString(uuid); } catch (Exception ignored) {continue;}
                if (!(owner.getValue() instanceof Map<?,?> raw)) continue;
                LinkedHashMap<String,Home> stored = homes.computeIfAbsent(uuid, a->new LinkedHashMap<>());
                for (var entry : raw.entrySet()) {
                    if (!(entry.getValue() instanceof Map<?,?> values)) continue;
                    String slot=String.valueOf(entry.getKey());
                    try {
                        String label=String.valueOf(values.get("name"));
                        stored.putIfAbsent(slot,new Home(String.valueOf(values.get("slot")),label,String.valueOf(values.get("world")),num(values.get("x")),num(values.get("y")),num(values.get("z")),(float)num(values.get("yaw")),(float)num(values.get("pitch"))));count++;
                    } catch(Exception ignored) {}
                }
            }
            Files.copy(candidate,DIR.resolve("legacy-original-backup.yml"), StandardCopyOption.REPLACE_EXISTING);
            save(); System.out.println("[PatilanciHomes] Imported "+count+" legacy homes from "+candidate+" (source untouched)");break;
        }
    }
    private double num(Object o) {return Double.parseDouble(String.valueOf(o));}
    private Home byName(ServerPlayerEntity p,String name) {return list(p).values().stream().filter(h -> h.name.equalsIgnoreCase(name)).findFirst().orElse(null);}
    private String keyByName(ServerPlayerEntity p,String name) {return list(p).entrySet().stream().filter(e->e.getValue().name.equalsIgnoreCase(name)).map(Map.Entry::getKey).findFirst().orElse(null);}
    private String nextSlot(ServerPlayerEntity p) {for(int i=1;i<1000;i++){String s="Home "+i;String key=Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));if(!list(p).containsKey(key))return key;}throw new IllegalStateException("All slots used");}
    private Home snapshot(ServerPlayerEntity p, String slot, String name) {
        Vec3d pos = p.getEntityPos();
        return new Home(slot,name,p.getEntityWorld().getRegistryKey().getValue().toString(),pos.x,pos.y,pos.z,p.getYaw(),p.getPitch());
    }
    private boolean storeHome(ServerPlayerEntity p, String key, Home home) {
        Home previous = list(p).put(key,home);
        if (save()) return true;
        if (previous == null) list(p).remove(key); else list(p).put(key,previous);
        msg(p,tr("read-only"));
        return false;
    }
    private boolean allowed(ServerPlayerEntity p) {if(!readOnly)return true;msg(p,tr("read-only"));return false;}
    private long secondsRemaining(Map<UUID,Long> clock, ServerPlayerEntity p, int seconds) {
        if(seconds == 0) return 0;
        return Math.max(0,(clock.getOrDefault(p.getUuid(),0L)+seconds*1000L-System.currentTimeMillis()+999)/1000);
    }
    private boolean delayOk(ServerPlayerEntity p, Map<UUID,Long> clock,int seconds) {
        long remaining=secondsRemaining(clock,p,seconds);
        if (remaining>0){msg(p,tr("cooldown","seconds",String.valueOf(remaining)));return false;}
        return true;
    }
    private int create(ServerPlayerEntity p,String name) {
        name=name.trim(); if(!allowed(p))return 0;
        if(!delayOk(p,lastCreate,settings.createCooldown()))return 0;
        if(name.isEmpty() || name.length()>settings.maxNameLength() || name.contains("\n")){msg(p,tr("invalid-name","max",String.valueOf(settings.maxNameLength())));return 0;}
        if(!dimensionAllowed(p)){msg(p,tr("restricted-world"));return 0;}
        Home existing=byName(p,name);
        if(existing!=null){
            if(!settings.allowOverwrite()){msg(p,tr("duplicate"));return 0;}
            String key=keyByName(p,name);
            if(!storeHome(p,key,snapshot(p,existing.slot,existing.name)))return 0;lastCreate.put(p.getUuid(),System.currentTimeMillis());msg(p,tr("updated","name",name));return 1;
        }
        if(limit(p) >= 0 && list(p).size()>=limit(p)){msg(p,tr("limit","limit",String.valueOf(limit(p))));return 0;}
        String key=nextSlot(p); String slot=new String(Base64.getDecoder().decode(key),StandardCharsets.UTF_8);
        if(!storeHome(p,key,snapshot(p,slot,name)))return 0;lastCreate.put(p.getUuid(),System.currentTimeMillis());msg(p,tr("created","name",name));return 1;
    }
    private int teleportName(ServerPlayerEntity p,String name){String k=keyByName(p,name);if(k==null){msg(p,tr("not-found"));return 0;}return teleport(p,k);}
    private int teleport(ServerPlayerEntity p,String key){
        Home h=list(p).get(key);if(h==null){msg(p,tr("not-found"));return 0;}
        Identifier id=h.world==null ? null : Identifier.tryParse(h.world); if(id==null){msg(p,"Invalid saved world.");return 0;}
        MinecraftServer server=p.getEntityWorld().getServer();
        ServerWorld world=server.getWorld(RegistryKey.of(RegistryKeys.WORLD,id));
        if(world==null){msg(p,"The saved world is not loaded: "+h.world);return 0;}
        if(settings.forbidCrossDimension() && world!=p.getEntityWorld()){msg(p,tr("cross-dimension"));return 0;}
        if(!delayOk(p,lastTeleport,settings.teleportCooldown()))return 0;
        if(!destinationAllowed(p,world,h))return 0;
        if(settings.warmupSeconds()>0) {
            long nonce=System.nanoTime(); pendingTeleport.put(p.getUuid(),nonce);
            Vec3d starting=p.getEntityPos();
            ServerWorld original=p.getEntityWorld();
            int ticks=settings.warmupSeconds()*20;
            msg(p,tr("warmup","seconds",String.valueOf(settings.warmupSeconds())));
            // Warmup uses server tick callbacks rather than background threads.
            Warmups.schedule(p, ticks, () -> {
                if(!Objects.equals(pendingTeleport.get(p.getUuid()),nonce)) return;
                pendingTeleport.remove(p.getUuid());
                if(settings.cancelOnMove() && (p.getEntityWorld()!=original || p.getEntityPos().squaredDistanceTo(starting)>0.05)) {msg(p,tr("moved"));return;}
                if(!p.isRemoved() && p.isAlive()) finishTeleport(p,world,h);
            });
            return 1;
        }
        return finishTeleport(p,world,h) ? 1 : 0;
    }
    private boolean destinationAllowed(ServerPlayerEntity p, ServerWorld world, Home h) {
        if(settings.forbidCrossDimension() && world!=p.getEntityWorld()){msg(p,tr("cross-dimension"));return false;}
        if(!Double.isFinite(h.x)||!Double.isFinite(h.y)||!Double.isFinite(h.z)
            ||!Float.isFinite(h.yaw)||!Float.isFinite(h.pitch)
            ||Math.abs(h.x)>30_000_000||Math.abs(h.z)>30_000_000){msg(p,tr("unsafe"));return false;}
        Box body=p.getDimensions(EntityPose.STANDING).getBoxAt(new Vec3d(h.x,h.y,h.z));
        if(body.minY<world.getBottomY()||body.maxY>world.getTopYInclusive()+1
            ||!world.getWorldBorder().contains(body)){msg(p,tr("unsafe"));return false;}
        // Load the entire landing area in the destination dimension before reading blocks.
        for(int cx=net.minecraft.util.math.MathHelper.floor(body.minX)>>4;cx<=net.minecraft.util.math.MathHelper.floor(body.maxX)>>4;cx++)
            for(int cz=net.minecraft.util.math.MathHelper.floor(body.minZ)>>4;cz<=net.minecraft.util.math.MathHelper.floor(body.maxZ)>>4;cz++)world.getChunk(cx,cz);
        if(!settings.safeTeleport())return true;
        // Test the full standing player, including fractional positions and block edges.
        if(!world.isSpaceEmpty(p,body)||world.containsFluid(body)
            ||world.isSpaceEmpty(p,new Box(body.minX,body.minY-0.05,body.minZ,body.maxX,body.minY,body.maxZ))){msg(p,tr("unsafe"));return false;}
        for(BlockPos pos:BlockPos.iterate(BlockPos.ofFloored(body.minX,body.minY-0.05,body.minZ),BlockPos.ofFloored(body.maxX,body.maxY,body.maxZ))) {
            BlockState state=world.getBlockState(pos);
            if(state.isIn(BlockTags.FIRE)||state.isOf(Blocks.LAVA)||state.isOf(Blocks.MAGMA_BLOCK)
                ||state.isOf(Blocks.CACTUS)||state.isOf(Blocks.CAMPFIRE)||state.isOf(Blocks.SOUL_CAMPFIRE)
                ||state.isOf(Blocks.SWEET_BERRY_BUSH)||state.isOf(Blocks.WITHER_ROSE)||state.isOf(Blocks.POWDER_SNOW)
                ||state.isOf(Blocks.NETHER_PORTAL)||state.isOf(Blocks.END_PORTAL)||state.isOf(Blocks.END_GATEWAY)) {
                msg(p,tr("unsafe"));return false;
            }
        }
        return true;
    }
    private boolean finishTeleport(ServerPlayerEntity p, ServerWorld world, Home h) {
        // A warmup may outlive changes to terrain or configuration.
        if(!destinationAllowed(p,world,h))return false;
        if(!p.teleport(world,h.x,h.y,h.z,Set.of(),h.yaw,h.pitch,true)) {
            msg(p,"Teleport failed. Please try again.");return false;
        }
        pendingTeleport.remove(p.getUuid());
        p.setVelocity(Vec3d.ZERO);
        p.onLanding();
        lastTeleport.put(p.getUuid(),System.currentTimeMillis());
        msg(p,tr("teleported","name",h.name));
        return true;
    }
    private static final class Warmups {
        private record Task(ServerPlayerEntity player, Runnable callback, long dueTick) {}
        private static final List<Task> tasks=new ArrayList<>();
        private static long tick=0;
        static void initialize(){net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server->{
            tick++;Iterator<Task> it=tasks.iterator();List<Runnable> ready=new ArrayList<>();
            while(it.hasNext()){Task t=it.next();if(t.dueTick<=tick){it.remove();ready.add(t.callback);}}
            ready.forEach(Runnable::run);
        });}
        static void schedule(ServerPlayerEntity p,int ticks,Runnable action){tasks.add(new Task(p,action,tick+ticks));}
    }
    private int deleteName(ServerPlayerEntity p,String name){String k=keyByName(p,name);return k==null?0:deleteSlot(p,k);}
    private int deleteSlot(ServerPlayerEntity p,String key){
        if(!key.equals(pendingDeletion.remove(p.getUuid()))){msg(p,"Open the delete confirmation first.");return 0;}
        if(!settings.allowDelete()){msg(p,tr("disabled"));return 0;}if(!allowed(p))return 0;Home removed=list(p).remove(key);if(removed==null)return 0;save();msg(p,tr("deleted","name",removed.name));menu(p);return 1;}
    private int renameName(ServerPlayerEntity p,String old,String updated){if(!settings.allowRename()){msg(p,tr("disabled"));return 0;}String k=keyByName(p,old);if(k==null)return 0;if(!allowed(p))return 0;updated=updated.trim();if(updated.isEmpty()||updated.length()>settings.maxNameLength()||(byName(p,updated)!=null && !old.equalsIgnoreCase(updated)))return 0;Home h=list(p).get(k);list(p).put(k,new Home(h.slot,updated,h.world,h.x,h.y,h.z,h.yaw,h.pitch));save();msg(p,tr("renamed","name",updated));return 1;}
    private int updateName(ServerPlayerEntity p,String name){String k=keyByName(p,name);return k==null?0:updateSlot(p,k);}
    private int updateSlot(ServerPlayerEntity p,String key){if(!settings.allowUpdate()){msg(p,tr("disabled"));return 0;}if(!allowed(p))return 0;Home h=list(p).get(key);if(h==null)return 0;if(!dimensionAllowed(p)){msg(p,tr("restricted-world"));return 0;}if(!storeHome(p,key,snapshot(p,h.slot,h.name)))return 0;msg(p,tr("updated","name",h.name));return 1;}
    private DialogActionButtonData button(String text,String tooltip,String command){return new DialogActionButtonData(new DialogButtonData(Text.literal(text),Optional.of(Text.literal(tooltip)),180),Optional.of(new SimpleDialogAction(new ClickEvent.RunCommand(command))));}
    private DialogActionButtonData submit(String text,String tooltip,String template){
        ParsedTemplate parsed=ParsedTemplate.CODEC.parse(JsonOps.INSTANCE,new JsonPrimitive(template)).getOrThrow();
        return new DialogActionButtonData(new DialogButtonData(Text.literal(text),Optional.of(Text.literal(tooltip)),180),Optional.of(new DynamicRunCommandDialogAction(parsed)));
    }
    private void form(ServerPlayerEntity p,String title,String subtitle,List<DialogInput> inputs,List<DialogActionButtonData> buttons){
        DialogCommonData data=new DialogCommonData(Text.literal(title),Optional.empty(),true,false,AfterAction.CLOSE,List.of(new PlainMessageDialogBody(Text.literal(subtitle),300)),inputs);
        p.openDialog(RegistryEntry.of(new MultiActionDialog(data,buttons,Optional.empty(),1)));
    }
    private void renameDialog(ServerPlayerEntity p,String key){
        Home home=list(p).get(key);if(home==null){msg(p,tr("not-found"));return;}
        form(p,"Rename • "+home.name,"Enter a new name for this home.",
            List.of(new DialogInput("name",new TextInputControl(260,Text.literal("New home name"),true,home.name,settings.maxNameLength(),Optional.empty()))),
            List.of(submit("Save name","Rename this home",HomeCommands.command("renamesave",key)+" $(name)"),button("Cancel","Keep current name",HomeCommands.command("detail",key))));
    }
    private int renameSlot(ServerPlayerEntity p,String key,String name){
        Home home=list(p).get(key);if(home==null){msg(p,tr("not-found"));return 0;}
        int result=renameName(p,home.name,name);if(result==1)detail(p,key);else renameDialog(p,key);return result;
    }
    private void show(ServerPlayerEntity p,String title,String subtitle,List<DialogActionButtonData> buttons){
        DialogCommonData common=new DialogCommonData(Text.literal(title),Optional.empty(),true,false,AfterAction.CLOSE,List.of(new PlainMessageDialogBody(Text.literal(subtitle),300)),List.of());
        p.openDialog(RegistryEntry.of(new MultiActionDialog(common,buttons,Optional.empty(),settings.columns())));
    }
    private void menu(ServerPlayerEntity p){
        List<DialogActionButtonData> buttons = new ArrayList<>();
        for (var entry : list(p).entrySet()) {
            buttons.add(button("⌂ " + entry.getValue().name,
                settings.tooltips().get("home"), HomeCommands.command("detail",entry.getKey())));
        }
        if (settings.showCreate()) {
            buttons.add(button(settings.buttons().get("create"),
                settings.tooltips().get("create"), "/sethome"));
        }
        show(p, settings.title(), "Saved homes: " + list(p).size() + " / "
            + (limit(p) < 0 ? "Unlimited" : limit(p)) + " • Choose a home below", buttons);
    }
    private void detail(ServerPlayerEntity p, String key){
        Home h = list(p).get(key);
        if (h == null) { msg(p, tr("not-found")); return; }
        List<DialogActionButtonData> buttons = new ArrayList<>();
        if (settings.showTeleport()) buttons.add(button(settings.buttons().get("teleport"), settings.tooltips().get("teleport"), HomeCommands.command("tp",key)));
        if (settings.showUpdate() && settings.allowUpdate()) buttons.add(button(settings.buttons().get("update"), settings.tooltips().get("update"), HomeCommands.command("update",key)));
        if (settings.showRename() && settings.allowRename()) buttons.add(button(settings.buttons().get("rename"), "Open name editor", HomeCommands.command("rename",key)));
        if (settings.showDelete() && settings.allowDelete()) buttons.add(button(settings.buttons().get("delete"), settings.tooltips().get("delete"), HomeCommands.command("delete",key)));
        buttons.add(button(settings.buttons().get("back"), settings.tooltips().get("back"), "/homes"));
        String info = (settings.showWorld() ? "World: " + h.world + "   " : "")
            + (settings.showCoordinates() ? "Position: " + (int) h.x + ", " + (int) h.y + ", " + (int) h.z : "");
        show(p, settings.detailTitle().replace("{name}", h.name), info.isEmpty() ? h.name : info, buttons);
    }
    private void confirmDelete(ServerPlayerEntity p,String key){Home h=list(p).get(key);if(h==null)return;pendingDeletion.put(p.getUuid(),key);
        show(p,settings.deleteTitle().replace("{name}",h.name),"Are you sure you want to delete \""+h.name+"\"?\nWorld: "+h.world+"   Position: "+(int)h.x+", "+(int)h.y+", "+(int)h.z+"\nOnly this home will be deleted.",List.of(button(settings.buttons().get("confirm"),settings.tooltips().get("confirm"),HomeCommands.command("confirmdelete",key)),button(settings.buttons().get("cancel"),settings.tooltips().get("cancel"),HomeCommands.command("detail",key))));
    }
}
