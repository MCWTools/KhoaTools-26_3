# KhoaTools (26.3) Project Status

## 2026-11-14: Switched to KhoaAPILibs (1.3.0)

- KhoaTools now REQUIRES the KhoaAPILibs mod (modid `khoaapi` >= 1.0.0, https://github.com/MCWTools/KhoaAPILibs). The jar is compiled against from `libs/KhoaAPILibs-1.0.0.jar` (not bundled); players install `KhoaAPILibs-<version>.jar` next to KhoaTools.
- Moved to the library (classes deleted here, imports now `khoa.api.*`): `KhoaPathfinding` + `GridPathfinder` / `PathOptions` / `Terrain` -> `khoa.api.path`, `KhoaBlockColor` -> `khoa.api.color`, `ChatCoordinates` -> `khoa.api.chat`, `ServerKey` / `Ticks` -> `khoa.api.util`. Code that called the old `khoa.tools.client.api.*` classes must switch to `khoa.api.path.*` / `khoa.api.color.KhoaBlockColor`.
- Kept here: `KeyCategory` ("khoatools" Controls category), `OptionUtil`, `FaceRect` (they use KhoaTools' own mixin / category).
- Verified: `gradlew build` BUILD SUCCESSFUL (jar `khoatools-1.3.0.jar`). NOT verified in game.

## Project

- Minecraft: 26.3
- Fabric Loader: 0.19.5
- Fabric API: 0.162.0+26.3
- Loom: 1.18.4 (resolved by Gradle)
- Java release: 25

## Port 26.1.2 -> 26.3 (2026-11-06)

- Source was replaced with the current `khoatools-26_1_2` source (all features incl. Camera, Better Chat editmsg/copyraw, Inventory Tweak, Custom Item Tab, Pulse editor, client give, Block Log, Hand Size, Totem Tweaks, Item Counter, Ruler, Light Level, AFK Alert).
- The previous (older) 26_3 copy is kept only as `old-26_3-src-backup-2026-11-06.zip` in this folder. It was NOT merged blindly: only newer pieces were kept (Playlist non-repeating shuffle in `PlaylistManager`/`PlaylistScreen`, `ChunkExporter` SerializableChunkData ctor, `setPermanentlyInvulnerable`, the sky/locator/logoutghost/el ports).
- 26.3 API changes handled (verified with javap against the Loom 26.3 jars): `Minecraft.screen/setScreen` -> `gui.screen()/gui.setScreen()`; `Gui.setOverlayMessage/setTitle/setSubtitle/setTimes/getChat/getGuiTicks` -> `gui.hud.*`; `Options.hideGui` -> `gui.hud.isHidden()`; `InputConstants.Type.KEYSYM` -> `KEYBOARD`; `InputConstants.isKeyDown(int)` (no window); `GameRenderer.getMainCamera()` -> `mainCamera()`; `LevelRenderer.allChanged()` -> `invalidateCompiledGeometry(level, options, camera, blockColors)`; `PoseStack.mulPose(Quaternionf)` -> `rotate`; `BlockPos.getCenter()` -> `Vec3.atCenterOf`; `ChatFormatting` colour/char helpers removed (ordinal <= WHITE + `TextColor.fromLegacyFormat`); LivingEntity swing fields -> `isSwinging()/getSwingAnimation()`.
- Mixins changed for 26.3: `EntityRenderDispatcherMixin.shouldRender` (+float), `BlockEntityRenderDispatcherMixin.tryExtractRenderState` (+boolean), `ItemInHandRendererMixin` now targets `FirstPersonHandsAndItemsRenderer.submitArmWithItem` (main arm read from the local player), Totem Tweaks item swap moved to new `ItemActivationRenderStateMixin` (constructor of `PlayerRenderState.ItemActivationRenderState`) with `TotemTweaksFeature.popping` flag.
- `fabric.mod.json` now depends on `minecraft ~26.3`.

## Verification

- `.\gradlew.bat clean build --offline` (GRADLE_USER_HOME=C:\Users\Khoa-Code-Play\.gradle) -> BUILD SUCCESSFUL (2026-11-06). Artifact `build/libs/khoatools-1.2.0.jar`, SHA-256 `22F2E2CA4E459BA16F02027EE2262F83507A72653EC130FBA2396A5CF16DB937`.
- `runClient` (dev, offline account) reached the title screen with "KhoaTools loaded"; a temporary probe loaded all 38 mixin target classes: no mixin failures after the fixes above (probe removed). No world was joined.
- NOT verified in-game: features that need a world (Hand Size, Totem Tweaks, Block Log, Camera watch, Find Leak, sky/logout ghost rendering, etc.). The sky package uses the older 26_3 port of the new GPU API; its runtime behaviour is untested.

## Fix: GUI clicks ignored (2026-11-07)

- Cause (verified in-game dev client + javap on 26.3 jars): 26.3 renumbered mouse buttons, `InputConstants.MOUSE_BUTTON_LEFT = 1` (was 0). Code compared `event.button()` with literal 0, so left clicks were ignored in `KhoaToolsScreen` (cards, Config, dropdowns), `ChatScreenMixin` (Ctrl+Shift / Ctrl+Alt clicks) and `InventoryTweakScreenMixin` (Ctrl+Alt delete).
- Fix: compare against `InputConstants.MOUSE_BUTTON_LEFT`.
- Verified: `gradlew.bat build --offline` BUILD SUCCESSFUL; dev client on title screen, clicking a card's Config button opened the config page. Card toggle / chat message and the chat/inventory mixins not re-tested in a world.

## Item / Block / Enchanted Book picker (2026-11-07)

- New setting type `Setting.Type.ITEM_LIST` (`Setting.itemList`, stored as `"kind:namespace:id"` strings, kind = item|block|book). Shown in a feature's config screen as a button "Edit list (N)" that opens `gui/picker/ItemPickerScreen`.
- Picker: tabs Items / Blocks / Enchanted Books (a setting may offer only some), search box, left = available, right = picked. Click = select, Shift+click = range, Ctrl+click = toggle, double click = add/remove, Add/Remove button (text follows the side of the selection), Ctrl+A, Enter, mouse wheel scroll. Esc / Back returns to the config screen. Saved on every change.
- Helpers: `PickData` (catalog + resolve), `PickFilter` (saved list + `matchesItem` / `matchesBlock`, `setting(...)`), `KhoaConfig.getStringList/putStringList`.
- First users: Item Counter ("Only count these items"), Block Log ("Only these blocks"); empty list = no filter.
- Items/blocks need a joined world (item components are not bound before; `new ItemStack` throws on the title screen), so the picker shows "Join a world to list items." until then.
- Verified: `gradlew.bat build --offline` OK; dev client in a new world: picker opened, 1630 items listed, tab switch to Enchanted Books (43), click + Shift+click range selected 6 cards and the button read "Add (6) >", double click added items to Picked (and persisted). NOT verified: Add button click, Remove, Ctrl+click, Esc back to config, Item Counter / Block Log filtering in play (automated input lost window focus).

### Picker update (2026-11-07, later)

- No free tabs any more: each config row fixes what it asks for. `List.of("item")` = items AND enchanted books in one list, `List.of("block")` = blocks only. Item Counter uses "item", Block Log uses "block".
- Added "Remove all" button (right side of the bottom bar) that clears the picked list.
- Verified: `gradlew.bat build --offline` BUILD SUCCESSFUL. Not re-run in game after this change.

## Sound Volume feature (2026-11-07)

- New card "Sound Volume" (`feature/soundvolume/SoundVolumeFeature`, persisted on/off): one 0-100 slider per `SoundSource` (master, music, records, weather, blocks, hostile, neutral = friendly, players, ambient, voice, UI). A never-touched slider follows the vanilla slider.
- `OptionsSoundVolumeMixin` overrides `Options.getSoundSourceVolume` while on (`getFinalSoundSourceVolume` and `SoundEngine.calculateVolume` go through it); vanilla option values are never modified. Toggle / slider change calls `SoundManager.refreshCategoryVolume` for every source so playing sounds follow at once.
- `SoundOptionsScreenMixin` (+ `OptionsSubScreenAccessor`): while on, Minecraft's Sound Options shows the header "Sound Volume feature is turned on" and all volume sliders are inactive (greyed out).
- Verified (javap on 26.3 jars): `Options.getSoundSourceVolume/getFinalSoundSourceVolume`, `SoundOptionsScreen.addOptions`, `OptionsList.addHeader/findOption`, `SoundManager.refreshCategoryVolume`. `gradlew.bat build --offline` BUILD SUCCESSFUL; dev client started and the target classes (Options, SoundOptionsScreen) loaded with the mixins applied, no crash.
- NOT verified in game: audible volume change, greyed sliders / header look, persisted-on at startup.

### Sound Volume: per-type Override (2026-11-07, later)

- Each sound type now has its own BOOL "Override <type>" row above its slider (default off). Only ticked types are overridden (`SoundVolumeFeature.overrides(source)` = card on AND tick on) and only their vanilla sliders are greyed out in Sound Options; unticked types are never touched. The header note still shows whenever the card is on.
- Verified: `gradlew.bat build --offline` BUILD SUCCESSFUL. Not run in game.


## Fake Player + Freecam speed sliders (2026-11-07)
- Freecam: `Freecam speed (horizontal)` / `(vertical)` sliders (1..100 blocks/s) in the Camera config; mouse wheel in freecam scales both and saves. Files: `camera/CameraWatch.java`, `camera/CameraFeature.java`.
- New feature `fakeplayer` (package `khoa.tools.client.feature.fakeplayer`): client-only `RemotePlayer` subclass (`FakePlayerEntity`), saved per server+dimension in `config/khoatools-fakeplayers.json`.
  Card click = create at your position + open editor (name, skin = player name or .png, slim, pose, main/off hand, head/chest/legs/feet in /give item syntax with red error text, effects with particles). `Give TP item` = client-only named stick (re-inserted into an empty slot if the server clears it); right click = fake player freezes at your position/pose/facing/walk+swing frame.
  Mixins: `FakePlayerMinecraftMixin` (startUseItem), `FakePlayerAvatarRendererMixin` (freeze animation), both in `khoatools.client.mixins.json`.
- Verified: `gradlew.bat build` -> BUILD SUCCESSFUL (Gradle 9.7.1, JDK 25.0.3). NOT verified in game: mixin targets at runtime, skin lookup by name, effect particles, TP item behaviour on a real server.

### Fake Player: copy player / delete / server safety (2026-11-07, later)
- `Copy player's data` button (+ `Copy from player` box, empty = you): copies name, skin (= that name), slim/normal, hands, armor incl. trims and all components (written as `id[component=value]`), and for yourself the hotbar + inventory. Position / pose / animation untouched. Other players only expose hands + armor to the client. Files: `FakePlayerCopy`, `FakePlayerItems.toText`, `FakePlayerInventoryScreen` (view stored inventory, click = main hand).
- Delete: editor (2-click) and list screen (2-click); also removes the TP item.
- TP item right click copies position, head rotation, pose and walk/swing frame as before; new toggle `TP item copies armor + off hand` (default off).
- Server safety: `FakePlayerPacketMixin` drops any creative-slot packet that carries a TP item, so it can never be written to the server; use is cancelled client side; entity creation errors are caught and reported in chat.
- Verified: `gradlew.bat build` BUILD SUCCESSFUL. Not run in game.

## Spawner Finder feature (2026-11-08)
- New card `spawnerfinder` (package `khoa.tools.client.feature.spawnerfinder`, persisted on/off), ported from the SpawnerFinder mod (26.1.2) without Baritone auto-mine and without the HUD list. Files: `SpawnerFinderFeature`, `SpawnerScanner` (loaded chunks, ~160 blocks, every 2 s), `SpawnerOverlay`, `SpawnerPath` + `SpawnerDigPath` (stare at a spawner N s -> safe path via `KhoaPathfinding` directly, fallback dig route), `SpawnerMobs` (vanilla list, all mobs, colours), `SpawnerLook` (shared icon look).
- Overlay: Gizmos.rect fills (always on top) on every block face turned to the player. Normal spawner = dark grey tile, trial spawner = copper top half + dark grey bottom half; centre square = 75 % of the tile in the mob's colour. Colours of the 12 vanilla mobs kept from SpawnerFinder (Cave Spider purple as in its overlay), other mobs get a stable hash colour.
- Picker: new kinds `spawner:<mob id>` / `trial:<mob id>` (`PickData.SPAWNER/TRIAL`, `Entry.swatch`), row `Setting.itemList(..., List.of("spawner"))`. Cards use the same tile icon (works on the title screen, no world needed). New `Setting.pickerToggle(...)`: the "Vanilla" tick next to the picker search box (on = only vanilla spawner mobs, off = every mob). Empty picked list = highlight every spawner.
- Settings: Spawners (picker), Pathfinding (bool), Path after looking (s), Max highlighted, Max distance.
- 26.3 API difference found: mob constants moved from `EntityType.ZOMBIE` to `EntityTypes.ZOMBIE`.
- Verified: `gradlew.bat build --offline` BUILD SUCCESSFUL, jar SHA-256 `296598D6EA2F40B922B3DFEC657405141D22A462E495593929941D7436269BB3` copied to the Modrinth 26.3 mods folder (hash matches). NOT run in game: overlay face winding/visibility, picker UI, tick box, path drawing.

## Illegal folder + Block Finder (2026-11-08)
- Spawner Finder moved to package khoa.tools.client.feature.illegal.spawnerfinder (config section id unchanged). New Block Finder in feature/illegal/blockfinder: block picker, red through-wall overlay (Gizmos cuboid), slider Overlay limit (10..5000, keeps only the nearest N via bounded heap) and slider Scan radius (8..96). To remove both: delete the feature/illegal folder, their lines in KhoaFeatures, and the spawner kinds in PickData/ItemPickerScreen/Setting.pickerToggle if wanted.
- Verified: gradlew.bat build --offline BUILD SUCCESSFUL. Not run in game.

## Mob Finder, Mode dropdown, extra keybind rows (2026-11-08, later)
- Mob Finder (feature/illegal/mobfinder): entity picker (kind entity, every registry type incl. player and special entities), hitbox box (partial-tick smoothed) and/or line, per-type colour, limit + max distance sliders, never marks yourself.
- feature/illegal/FinderMode: Mode dropdown (Box / Lines / Box and Lines) used by Spawner Finder, Block Finder, Mob Finder. Spawner tile opacity lowered (bg alpha 0x60, copper 0x70, centre 0xA0).
- Setting.Type.KEYBIND + Setting.keybind + KhoaKeybinds.setting(section,key,label,desc,onPress): optional extra key row in any feature config; KhoaKeybinds.tick fires them. Used: Spawner Finder 'Pathfinding key', Block/Mob Finder 'Mode key'.
- Controls screen: KhoaTools category now has 'Open KhoaTools menu' (key.khoatools.open_gui, unbound by default).
- Verified: gradlew.bat build --offline BUILD SUCCESSFUL. Not run in game.

## Block Finder colours/outline/path, StarePath, KhoaBlockColor (2026-11-08, later 2)
- api/KhoaBlockColor: averageRgb(Block|BlockState) / tryAverageRgb / brighten / clearCache. Reads the block particle texture (BlockStateModelSet.getParticleMaterial -> sprite name -> textures/<path>.png via ResourceManager + NativeImage), skips alpha<16, first frame of animated strips, no biome tint (grass/leaves give grey), cached per Block.
- Block Finder: fill = texture colour at alpha 0x48, outline = colour brightened 45 percent, opaque; same-type neighbours merge outlines (edge drawn only when 1 / 3 / 2-diagonal of the 4 cells around it are filled, owner = lowest cell; checked with a Python simulation: 1 block 12, 2x1x1 16 segments, 3x3x3 36, 3x3 slab 28). Setting Use block colour (off = red). Gizmo fills + edge lines are precomputed at scan time.
- feature/illegal/StarePath: instance class from the old SpawnerPath. heavy profile (Spawner: 8 goals, 250k nodes, dig route) / light profile (Block Finder: 4 goals within 6 blocks, 60k nodes / 40 ms, weight 1.8, straight orange line when no walkable path). Block Finder: Pathfinding bool, Path after looking (s), Pathfinding key.
- Spawner overlay: tiles now alpha 0x30/0x3C, centre square outlined, opaque outline cuboid (grey / bright copper).
- Verified: gradlew.bat build --offline BUILD SUCCESSFUL. Not run in game (texture colours, gizmo rect stroke, path).

- KhoaBlockColor now caches and computes per BlockState (averageRgb(BlockState)); Block overloads use the default state. Block Finder colours each hit by its real state (outline merging still groups by Block). Build OK, not run in game.

- Block Finder fill is now per face: KhoaBlockColor.faceColors(state)/averageRgb(state, face) read the model quads (BlockStateModelSet.get(state).collectParts -> getQuads(dir) -> materialInfo().sprite()), fallback = particle colour. Only exposed faces (no same-type neighbour) facing the player are drawn via feature/illegal/FaceRect (Gizmos.rect). Outline colour still from the particle colour. Build OK, not run in game.

## Waypoints feature (2026-11-08)
- New feature `waypoints` in `src/client/java/khoa/tools/client/feature/waypoints/` (Waypoint, WaypointStore, WaypointBadge, WaypointsFeature, WaypointHud, WaypointScreen, WaypointEditScreen, XaeroPickScreen, XaeroImport). Registered in KhoaFeatures.
- Card click / `/khoatoolsclient waypoints` / open key -> manager GUI, opened one tick later (flag in END_CLIENT_TICK).
- Manager: Create, Edit (grey until exactly 1 selected), Delete (press twice), Show/Hide, Import Xaero, Sort (Distance/Name/Created/Modified/Color); multi-select (click, Shift range, Ctrl/tick box toggle, Ctrl+A). Death points always on top (Latest death, Old death...).
- Create/Edit screen: name, X/Y/Z, Aimed block / Standing block buttons, R/G/B sliders + hex + 16 Xaero colours + "Block colour" (average top texture of the block via KhoaBlockColor).
- World: depth-tested fill + outline cuboid on the waypoint block (no always-on-top). HUD board: 2D arrow with the initial (skull for deaths), projected from camera vectors; looking within 26 GUI px of one zooms it and shows name, distance and death timer.
- Deaths: recorded when the player starts dying (block under feet), max N kept (default 5). Item despawn timer 6000 ticks, only counts down while within "timer_range" blocks (horizontal) in the same dimension.
- Xaero import: .minecraft/xaero/minimap/<world>/dim%*/*.txt, auto-matches current world/server, otherwise a picker; colours 0-15 mapped to Xaero RGB; gui.xaero_deathpoint[_old] -> death points.
- Data file: config/khoatools_waypoints.json (per server / singleplayer world). Settings in config/khoatools.json section `waypoints`.
- Build: `gradlew.bat build --offline` BUILD SUCCESSFUL. Not verified in game yet.

## Waypoints v2: groups, areas, files, Xaero maps (2026-11-08)
- Storage moved to `.minecraft/khoatools/waypoints/multiplayer/<server ip>/` (servers) or `.../waypoints/<world name>/` (singleplayer): `Waypoints.json` (every waypoint with an id) + `Groups.json` (id, name, visible, ids of waypoints and child groups) + `backup/<time>[_why]/` (copy of both files when a world is first opened and before every delete/unpack; newest 25 kept). Old `config/khoatools_waypoints.json` is migrated once. Writes go through a temp file + move.
- New classes: `Group`, `WorldData` (tree logic: move with cycle guard, createGroup, unpack, deleteGroup recursive, recursive visibility), `AreaRender`, `XaeroMapBridge`, `GroupNameScreen`, mixin `WaypointPickMixin` (blocks use/attack while picking a corner).
- Groups in the list: Group button (selection -> named group), double click opens a group, `..` row / Backspace/Esc goes up, drag selected rows onto a group (or `..`) to move, groups inside groups, Show/Hide per row (eye) or for the selection, group Rename/Unpack/Delete (delete removes everything inside, press twice). Death points always first.
- Area waypoints: Mode dropdown Single/Area in the editor, two XYZ rows each with `Select position` (hides the GUI, action bar tells you to shift + right click a block, Esc cancels). Area is drawn as one box (outer faces + 12 outer edges only); blocks that hold a visible single waypoint are cut out of the fill/edges. Board arrow sits at the centre of the box; distance = to the nearest point of the box.
- Xaero maps: `XaeroMapBridge` (reflection) pushes the visible waypoints of the current dimension into Xaero's third-party waypoint list (`MinimapWorldContainer.getThirdPartyWaypointManager().get(khoatools:waypoints)`), which the minimap and the world map (`SupportXaeroMinimap.convertWaypoints`) both read. Deaths use Xaero's DEATH / OLD_DEATH purpose. Setting `xaero_map`.
- Build: `gradlew.bat build --offline` BUILD SUCCESSFUL. Nothing verified in game yet.

## Waypoints v3: area edit mode (2026-11-08)
- Area waypoints are now lists of regions (`boxes` + `blocks` in Waypoints.json); old two-corner areas migrate automatically.
- `RegionGeometry` (pure Java) builds merged outer faces + outline edges of the union; checked with a standalone randomized test (4000 cases, 0 failures).
- `AreaEditSession`: hold right click to draw a box, press inside a box to resize its nearest corner, Enter save, Backspace/Ctrl+Z undo, Delete remove, Esc cancel; max side 100 (setting).
- Build: `gradlew.bat build --offline` OK. Not verified in game yet.

## Air Place + Block Text (2026-11-08)
- Air Place (`feature/airplace`): toggle card, slider distance 1-12, right click on nothing places the held block; preview box.
- Block Text (`feature/blocktext`): pulse card, text on a block side drawn with rect gizmos from the Minecraft ascii font, auto scale, GUI, saved in khoatools/blocktext/<world>/BlockText.json, removed when the block is air, Shift+right click edits.
- Build: `gradlew.bat build --offline` OK. Not verified in game.

## GUI open animation + Auto Reconnect countdown (2026-11-09)

- New `gui/GuiAnim.java`; settings (Animation bool, Animation Length slider 100-2000 ms, Animation Mode Bottom up / Outside in) live in the "GUI" card (`OpenGuiFeature`), stored in config section `gui`. `KhoaToolsScreen.renderList` fades the top bar, then cards (quad in-out, snake order; Outside in = column-parallel). Cards are drawn from their scrolled position, hidden cards stay alpha 0, cards that are not fully shown are not clickable.
- `AutoReconnectFeature`: countdown "Auto reconnect in Ns" + progress bar on the DisconnectedScreen (Fabric `ScreenEvents.afterExtract`).
- Verified: `gradlew.bat build --offline` BUILD SUCCESSFUL. NOT verified in-game (visual look, timing, countdown position).

## Ghost Item (2026-11-09)

- New card `feature/ghostitem/GhostItemFeature.java` (registered in KhoaFeatures): copies the offhand item into the empty selected hotbar slot client-side; removed on slot change / offhand change / container screen. Build OK (`gradlew.bat build --offline`); NOT tested in-game.

## Unlimited saved hotbars (Inventory Tweak option "Unlimited saved hotbars (creative)", off by default)
- Saved Hotbars tab of the creative inventory: the 9 vanilla rows, then extra rows (hotbar 10 and up), 5 more loaded each time you scroll to the bottom. Extra hotbars live in `config/khoatools-hotbars.json`; vanilla `hotbar.nbt` is only touched through the game's own HotbarManager when you save to numbers 1-9.
- "Save / Load" button in the tab opens a small vanilla-style box: number box (1+), Save, Load. 1-9 = vanilla slots, 10+ = extra. Load is creative only.
- Files: `feature/inventorytweak/{HotbarRows,SavedHotbarStore,SaveLoadHotbarScreen}.java`, mixin `CreativeModeInventoryScreenMixin`.
- Verified in the dev client (screenshots of the tab with a saved extra row, scrolling, the box). Not tested: real clicks on Save/Load, other mods' tabs, multiplayer.

## 2026-11-18 /bettersummon
- feature/bettersummon/BetterSummonCommand: `/bettersummon <1-10000> <summon args>`; client command with requires(hasSingleplayerServer) so it is hidden/refused on servers; runs on the integrated server with the player's command source (cheats rules apply), parses once. Built OK, NOT yet tested in game.

## 2026-11-18 Far mob LOD + far mob AI
- Client card "Far Mob LOD" (feature/farlod): far/small looking mobs skip all render layers (LivingEntityRendererMixin.shouldRenderLayers). Slider scale 50-200 %, threshold = 32 blocks * scale * size/1.8.
- Server `/khoatools farmobai [distance] [interval]` (command/FarMobAiFeature + MobAiMixin on Mob.serverAiStep). Khoa found render count is the bottleneck, so client LOD is the focus.
- Built OK; mixins NOT yet verified in game (dev client run was killed for low memory). Planned: tier 2 (no shadow/nametag/outer model layer), animation throttle, measurement counter.
