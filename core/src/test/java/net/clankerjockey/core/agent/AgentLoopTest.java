package net.clankerjockey.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.clankerjockey.core.engine.InferenceBackend;
import net.clankerjockey.core.memory.PromptAssembler;
import net.clankerjockey.core.tools.CoreTools;
import net.clankerjockey.core.tools.ToolExecutor;
import net.clankerjockey.core.tools.ToolRegistry;
import net.clankerjockey.core.tools.ToolResult;
import net.clankerjockey.core.events.EventLog;
import net.clankerjockey.core.events.EventPriority;
import net.clankerjockey.core.events.SemanticEvent;
import net.clankerjockey.core.world.BlockInfo;
import net.clankerjockey.core.world.PlaceMemory;
import net.clankerjockey.core.world.ContainerStore;
import net.clankerjockey.core.tasks.TaskManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

class AgentLoopTest {

    @TempDir
    Path tmpDir;

    private static final String SYSTEM = "You are Vera, a warm companion in Minecraft.";

    private static AgentLoop loop(InferenceBackend backend) {
        return loop(backend, new TestWorld(), AgentLoopConfig.defaults());
    }

    private static AgentLoop loop(InferenceBackend backend, TestWorld world, AgentLoopConfig config) {
        ToolRegistry reg = new ToolRegistry();
        reg.registerAll(CoreTools.all());
        return new AgentLoop(backend, reg, new ToolExecutor(reg), config, SYSTEM);
    }

    private static AgentContext context(TestWorld world) {
        return AgentContext.builder("Sean", world)
                .owner(true)
                .history(List.of(new PromptAssembler.Turn("Hi", "Hello!")))
                .build();
    }

    @Test
    void singleToolCallThenRespond() throws Exception {
        TestWorld world = new TestWorld();
        ScriptedBackend backend = new ScriptedBackend(
                "{\"tool\":\"get_inventory\",\"arguments\":{}}",
                "{\"tool\":\"respond\",\"arguments\":{\"text\":\"I'm carrying nothing.\"}}");

        AgentResponse r = loop(backend).run("What do you have?", context(world));

        assertEquals("I'm carrying nothing.", r.text());
        assertEquals(2, r.iterations());
        assertEquals(1, r.toolCalls().size());
        assertEquals("get_inventory", r.toolCalls().get(0).call().name());
        assertEquals(ToolResult.Status.SUCCESS, r.toolCalls().get(0).result().status());
        assertEquals(1, world.inventoryCalls);
        assertFalse(r.limitExceeded());
        assertFalse(r.interrupted());
    }

    @Test
    void multiCallChainWithoutNewPlayerMessage() throws Exception {
        TestWorld world = new TestWorld();
        ScriptedBackend backend = new ScriptedBackend(
                "{\"tool\":\"get_self_state\",\"arguments\":{}}",
                "{\"tool\":\"follow_player\",\"arguments\":{\"player\":\"Sean\",\"distance\":3}}",
                "{\"tool\":\"respond\",\"arguments\":{\"text\":\"Right behind you!\"}}");

        AgentResponse r = loop(backend).run("Come over here and follow me.", context(world));

        assertEquals("Right behind you!", r.text());
        assertEquals(3, r.iterations());
        assertEquals(2, r.toolCalls().size());
        assertEquals("get_self_state", r.toolCalls().get(0).call().name());
        assertEquals("follow_player", r.toolCalls().get(1).call().name());
        assertEquals(1, world.followCalls);
        assertTrue(world.isFollowing());
        assertTrue(backend.prompts.get(1).contains("TOOL RESULT"), "tool result must feed back into the next prompt");
        assertTrue(backend.prompts.get(1).contains("get_self_state"));
    }

    @Test
    void repeatedIdenticalCallsGetOneNudgeThenRespond() throws Exception {
        TestWorld world = new TestWorld();
        ScriptedBackend backend = new ScriptedBackend(
                "{\"tool\":\"get_inventory\",\"arguments\":{}}",
                "{\"tool\":\"get_inventory\",\"arguments\":{}}",
                "{\"tool\":\"get_inventory\",\"arguments\":{}}");

        AgentResponse r = loop(backend).run("check again", context(world));

        assertEquals("Done.", r.text(), "after the nudge the model must respond instead of repeating");
        assertEquals(2, world.inventoryCalls, "the duplicate must be detected before a third execution");
        assertEquals(2, r.toolCalls().size());
        assertFalse(r.limitExceeded(), "a single nudge is enough to recover");
    }

    @Test
    void repeatedIdenticalCallsHardStopAfterNudge() throws Exception {
        TestWorld world = new TestWorld();
        ScriptedBackend backend = new ScriptedBackend(
                "{\"tool\":\"get_inventory\",\"arguments\":{}}",
                "{\"tool\":\"get_inventory\",\"arguments\":{}}",
                "{\"tool\":\"get_inventory\",\"arguments\":{}}",
                "{\"tool\":\"get_inventory\",\"arguments\":{}}",
                "{\"tool\":\"get_inventory\",\"arguments\":{}}");

        AgentResponse r = loop(backend).run("check again", context(world));

        assertTrue(r.limitExceeded(), "a model that repeats even after the nudge must trip the safety limit");
        assertEquals(2, world.inventoryCalls);
        assertEquals(2, r.toolCalls().size());
        assertTrue(r.text().toLowerCase().contains("same thing"));
    }

    @Test
    void consecutiveFailuresStopTheLoop() throws Exception {
        TestWorld world = new TestWorld();
        world.goToPlayerResult = net.clankerjockey.core.world.ActionResult.failed("Player is not online.");
        ScriptedBackend backend = new ScriptedBackend(
                "{\"tool\":\"go_to_player\",\"arguments\":{\"player\":\"Ghost1\"}}",
                "{\"tool\":\"go_to_player\",\"arguments\":{\"player\":\"Ghost2\"}}",
                "{\"tool\":\"go_to_player\",\"arguments\":{\"player\":\"Ghost3\"}}");

        AgentResponse r = loop(backend, world, AgentLoopConfig.defaults()).run("go to ghost", context(world));

        assertTrue(r.limitExceeded());
        assertEquals(3, world.goToPlayerCalls);
        assertTrue(r.text().toLowerCase().contains("trouble"));
    }

    @Test
    void unknownToolFeedsFailureBackAndLoopRecovers() throws Exception {
        TestWorld world = new TestWorld();
        ScriptedBackend backend = new ScriptedBackend(
                "{\"tool\":\"no_such_tool\",\"arguments\":{}}",
                "{\"tool\":\"respond\",\"arguments\":{\"text\":\"I don't know that one, sorry.\"}}");

        AgentResponse r = loop(backend).run("do the thing", context(world));

        assertEquals("I don't know that one, sorry.", r.text());
        assertEquals(1, r.toolCalls().size());
        assertEquals(ToolResult.Status.FAILED, r.toolCalls().get(0).result().status());
        assertFalse(r.limitExceeded());
    }

    @Test
    void cancellationStopsTheLoop() throws Exception {
        TestWorld world = new TestWorld();
        AgentContext ctx = context(world);
        ctx.requestCancel();

        AgentResponse r = loop(new ScriptedBackend()).run("hello", ctx);

        assertTrue(r.interrupted());
        assertTrue(r.toolCalls().isEmpty());
    }

    @Test
    void cancellationDuringLoopInterruptsMidTurn() throws Exception {
        TestWorld world = new TestWorld();
        AgentContext ctx = context(world);
        ScriptedBackend backend = new ScriptedBackend(
                "{\"tool\":\"get_inventory\",\"arguments\":{}}",
                "{\"tool\":\"get_inventory\",\"arguments\":{}}") {
            @Override
            public String generate(String prompt, net.clankerjockey.core.engine.GenOptions opts) {
                String out = super.generate(prompt, opts);
                ctx.requestCancel();
                return out;
            }
        };

        AgentResponse r = loop(backend).run("hello", ctx);

        assertTrue(r.interrupted(), "cancellation mid-turn must interrupt");
        assertTrue(r.toolCalls().size() <= 1);
    }

    @Test
    void grammarIsAttachedToEveryRequest() throws Exception {
        ScriptedBackend backend = new ScriptedBackend(
                "{\"tool\":\"respond\",\"arguments\":{\"text\":\"Hi!\"}}");

        loop(backend).run("hi", context(new TestWorld()));

        assertTrue(backend.options.get(0).grammar() != null);
        assertTrue(backend.options.get(0).grammar().contains("root ::="));
        assertTrue(backend.options.get(0).grammar().contains("\\\"get_inventory\\\""));
        assertTrue(backend.options.get(0).grammar().contains("\\\"follow_player\\\""));
    }

    @Test
    void initialPromptContainsToolDocsMemoryAndWorld() throws Exception {
        TestWorld world = new TestWorld();
        ScriptedBackend backend = new ScriptedBackend();
        AgentContext ctx = AgentContext.builder("Sean", world)
                .owner(true)
                .history(List.of(new PromptAssembler.Turn("Hi", "Hello!")))
                .build();

        loop(backend).run("what's around me", ctx);

        String prompt = backend.prompts.get(0);
        assertTrue(prompt.contains("Available tools:"));
        assertTrue(prompt.contains("get_nearby_entities"));
        assertTrue(prompt.contains("Recent conversation:"));
        assertTrue(prompt.contains("World context:"));
    }

    @Test
    void spatialMemoryChainStopsFollowingAndGoesHome() throws Exception {
        TestWorld world = new TestWorld();
        PlaceMemory places = new PlaceMemory(tmpDir.resolve("places.json"));
        places.remember("home", 10, 64, -20);
        ScriptedBackend backend = new ScriptedBackend(
                "{\"tool\":\"stop_following\",\"arguments\":{}}",
                "{\"tool\":\"recall_place\",\"arguments\":{\"name\":\"home\"}}",
                "{\"tool\":\"go_to_coordinates\",\"arguments\":{\"x\":10,\"y\":64,\"z\":-20}}",
                "{\"tool\":\"respond\",\"arguments\":{\"text\":\"Home sweet home.\"}}");
        AgentContext ctx = AgentContext.builder("Sean", world).owner(true).places(places).build();

        AgentResponse r = loop(backend).run("Stop following me and go back to the house.", ctx);

        assertEquals("Home sweet home.", r.text());
        assertEquals(List.of("stop_following", "recall_place", "go_to_coordinates"),
                r.toolCalls().stream().map(t -> t.call().name()).toList(),
                "milestone-1 chain: stop follow -> recall home -> navigate");
        assertEquals(1, world.stopCalls);
        assertEquals(1, world.goToCalls);
        assertFalse(r.limitExceeded());
        assertTrue(backend.prompts.get(2).contains("10"), "recall result must feed back into the prompt");
    }

    @Test
    void rememberHereThenRecallPersistsPlace() throws Exception {
        TestWorld world = new TestWorld();
        PlaceMemory places = new PlaceMemory(tmpDir.resolve("places.json"));
        ScriptedBackend backend = new ScriptedBackend(
                "{\"tool\":\"remember_here\",\"arguments\":{\"name\":\"base camp\"}}",
                "{\"tool\":\"recall_place\",\"arguments\":{\"name\":\"base camp\"}}",
                "{\"tool\":\"respond\",\"arguments\":{\"text\":\"Noted!\"}}");
        AgentContext ctx = AgentContext.builder("Sean", world).owner(true).places(places).build();

        AgentResponse r = loop(backend).run("Remember this spot as base camp.", ctx);

        assertEquals("Noted!", r.text());
        assertEquals(1, places.all().size());
        assertEquals(1.0, places.recall("base camp").get().x(), 0.001, "TestWorld starts at x=1");
        assertEquals("recall_place", r.toolCalls().get(1).call().name());
    }

    @Test
    void findNearbyBlockResultFeedsBackIntoPrompt() throws Exception {
        TestWorld world = new TestWorld();
        world.nearestBlock = java.util.Optional.of(new BlockInfo("iron_ore", 5, 63, 8, 6.4));
        ScriptedBackend backend = new ScriptedBackend(
                "{\"tool\":\"find_nearby_block\",\"arguments\":{\"block\":\"iron_ore\"}}",
                "{\"tool\":\"respond\",\"arguments\":{\"text\":\"There's iron about 6 blocks away.\"}}");

        AgentResponse r = loop(backend).run("Help me find some iron.", context(world));

        assertEquals("There's iron about 6 blocks away.", r.text());
        assertEquals(1, r.toolCalls().size());
        assertEquals("find_nearby_block", r.toolCalls().get(0).call().name());
        assertEquals("iron_ore", r.toolCalls().get(0).result().data().get("block"));
        assertTrue(backend.prompts.get(1).contains("iron_ore"));
    }

    @Test
    void taskLifecycleChainsStartStatusCancel() throws Exception {
        TestWorld world = new TestWorld();
        TaskManager tasks = new TaskManager();
        ScriptedBackend backend = new ScriptedBackend(
                "{\"tool\":\"start_task\",\"arguments\":{\"description\":\"collect 32 iron ore\"}}",
                "{\"tool\":\"get_task_status\",\"arguments\":{\"task_id\":\"task-1\"}}",
                "{\"tool\":\"cancel_task\",\"arguments\":{\"task_id\":\"task-1\"}}",
                "{\"tool\":\"respond\",\"arguments\":{\"text\":\"Task cancelled.\"}}");
        AgentContext ctx = AgentContext.builder("Sean", world).owner(true).tasks(tasks).build();

        AgentResponse r = loop(backend).run("Start a task to collect 32 iron ore, then cancel it.", ctx);

        assertEquals("Task cancelled.", r.text());
        assertEquals(List.of("start_task", "get_task_status", "cancel_task"),
                r.toolCalls().stream().map(t -> t.call().name()).toList());
        assertEquals(1, tasks.all().size());
        assertTrue(tasks.get("task-1").get().isTerminal());
        assertTrue(r.toolCalls().get(0).result().data().containsKey("task_id"));
        assertFalse(r.limitExceeded());
    }

    @Test
    void giveItemFlowChecksInventoryThenGives() throws Exception {
        TestWorld world = new TestWorld();
        world.items.put("diamond", 3);
        ScriptedBackend backend = new ScriptedBackend(
                "{\"tool\":\"has_item\",\"arguments\":{\"item\":\"diamond\"}}",
                "{\"tool\":\"give_item\",\"arguments\":{\"player\":\"Sean\",\"item\":\"diamond\",\"count\":1}}",
                "{\"tool\":\"respond\",\"arguments\":{\"text\":\"Here you go!\"}}");

        AgentResponse r = loop(backend).run("Give me a diamond.", context(world));

        assertEquals("Here you go!", r.text());
        assertEquals(List.of("has_item", "give_item"),
                r.toolCalls().stream().map(t -> t.call().name()).toList());
        assertEquals(1, world.giveCalls);
        assertEquals(2, world.items.get("diamond"));
        assertTrue(backend.prompts.get(1).contains("diamond"), "has_item result must feed back");
        assertFalse(r.limitExceeded());
    }

    @Test
    void equipAndConsumeActionsWork() throws Exception {
        TestWorld world = new TestWorld();
        world.items.put("cooked_beef", 2);
        ScriptedBackend backend = new ScriptedBackend(
                "{\"tool\":\"equip_item\",\"arguments\":{\"item\":\"cooked_beef\"}}",
                "{\"tool\":\"consume_item\",\"arguments\":{\"item\":\"cooked_beef\",\"count\":1}}",
                "{\"tool\":\"respond\",\"arguments\":{\"text\":\"That hit the spot!\"}}");

        AgentResponse r = loop(backend).run("Equip and eat some beef.", context(world));

        assertEquals("That hit the spot!", r.text());
        assertEquals("cooked_beef", world.equippedItem);
        assertEquals(1, world.consumeCalls);
        assertEquals(1, world.items.get("cooked_beef"));
    }

    @Test
    void startCollectTaskCreatesRunnableTaskMetadata() throws Exception {
        TestWorld world = new TestWorld();
        TaskManager tasks = new TaskManager();
        ScriptedBackend backend = new ScriptedBackend(
                "{\"tool\":\"start_collect_task\",\"arguments\":{\"block\":\"iron_ore\",\"count\":4}}",
                "{\"tool\":\"get_task_status\",\"arguments\":{\"task_id\":\"task-1\"}}",
                "{\"tool\":\"respond\",\"arguments\":{\"text\":\"On it!\"}}");
        AgentContext ctx = AgentContext.builder("Sean", world).owner(true).tasks(tasks).build();

        AgentResponse r = loop(backend).run("Collect 4 iron ore for me.", ctx);

        assertEquals("On it!", r.text());
        assertEquals("collect", tasks.get("task-1").get().data().get("type"));
        assertEquals("iron_ore", tasks.get("task-1").get().data().get("block"));
        assertEquals(4, tasks.get("task-1").get().data().get("count"));
        assertFalse(r.limitExceeded());
    }

    @Test
    void recentEventsAreInjectedIntoPrompt() throws Exception {
        TestWorld world = new TestWorld();
        EventLog events = new EventLog();
        events.add(SemanticEvent.of(EventPriority.P0, "REFLEX", "You evaded a creeper."));
        ScriptedBackend backend = new ScriptedBackend();
        AgentContext ctx = AgentContext.builder("Sean", world).owner(true).events(events).build();

        loop(backend).run("hi", ctx);

        String prompt = backend.prompts.get(0);
        assertTrue(prompt.contains("Recent events:"), "event log must reach the prompt");
        assertTrue(prompt.contains("evaded a creeper"));
    }

    @Test
    void runNoticeBuildsSystemTurn() throws Exception {
        TestWorld world = new TestWorld();
        ScriptedBackend backend = new ScriptedBackend(
                "{\"tool\":\"respond\",\"arguments\":{\"text\":\"Watch out, a creeper!\"}}");

        AgentResponse r = loop(backend).runNotice("a creeper is 3.2m from Sean", context(world));

        assertEquals("Watch out, a creeper!", r.text());
        String prompt = backend.prompts.get(0);
        assertTrue(prompt.contains("System notice: a creeper is 3.2m from Sean"));
        assertTrue(!prompt.contains("\nPlayer: a creeper"), "notice turns must not be player messages");
        assertTrue(r.toolCalls().isEmpty(), "a pure respond turn has no tool calls");
    }

    @Test
    void attackAndDefendActionsWork() throws Exception {
        TestWorld world = new TestWorld();
        world.entities = List.of(new net.clankerjockey.core.world.EntityInfo("zombie", 3.0, 3, 64, 3, true, "front"));
        ScriptedBackend backend = new ScriptedBackend(
                "{\"tool\":\"attack_entity\",\"arguments\":{\"type\":\"zombie\"}}",
                "{\"tool\":\"defend_player\",\"arguments\":{\"player\":\"Sean\"}}",
                "{\"tool\":\"respond\",\"arguments\":{\"text\":\"I've got you!\"}}");

        AgentResponse r = loop(backend).run("Kill that zombie and protect me.", context(world));

        assertEquals("I've got you!", r.text());
        assertEquals(1, world.attackCalls);
        assertEquals(1, world.defendCalls);
        assertEquals(List.of("attack_entity", "defend_player"),
                r.toolCalls().stream().map(t -> t.call().name()).toList());
    }

    @Test
    void fleeFromEntityWorks() throws Exception {
        TestWorld world = new TestWorld();
        world.entities = List.of(new net.clankerjockey.core.world.EntityInfo("creeper", 2.0, 2, 64, 2, true, "behind"));
        ScriptedBackend backend = new ScriptedBackend(
                "{\"tool\":\"flee_from_entity\",\"arguments\":{\"type\":\"creeper\",\"distance\":16}}",
                "{\"tool\":\"respond\",\"arguments\":{\"text\":\"Backing away...\"}}");

        AgentResponse r = loop(backend).run("Get away from that creeper!", context(world));

        assertEquals("Backing away...", r.text());
        assertEquals(1, world.fleeCalls);
        assertEquals("flee_from_entity", r.toolCalls().get(0).call().name());
    }

    @Test
    void attackEntityIsDeniedForNonOwner() throws Exception {
        TestWorld world = new TestWorld();
        world.entities = List.of(new net.clankerjockey.core.world.EntityInfo("zombie", 2.0, 2, 64, 2, true, "front"));
        ScriptedBackend backend = new ScriptedBackend(
                "{\"tool\":\"attack_entity\",\"arguments\":{\"type\":\"zombie\"}}",
                "{\"tool\":\"respond\",\"arguments\":{\"text\":\"I can't do that.\"}}");
        AgentContext ctx = AgentContext.builder("Stranger", world).owner(false).build();

        AgentResponse r = loop(backend).run("Kill that zombie.", ctx);

        assertEquals("I can't do that.", r.text());
        assertEquals(ToolResult.Status.DENIED, r.toolCalls().get(0).result().status(),
                "combat must be enforced below the model");
        assertEquals(0, world.attackCalls);
    }

    @Test
    void containerFlowOpensPutsAndTakes() throws Exception {
        TestWorld world = new TestWorld();
        world.items.put("diamond", 4);
        ContainerStore containers = new ContainerStore(tmpDir.resolve("containers.json"));
        ScriptedBackend backend = new ScriptedBackend(
                "{\"tool\":\"open_container\",\"arguments\":{\"name\":\"chest\"}}",
                "{\"tool\":\"put_in_container\",\"arguments\":{\"name\":\"chest\",\"item\":\"diamond\",\"count\":3}}",
                "{\"tool\":\"take_from_container\",\"arguments\":{\"name\":\"chest\",\"item\":\"diamond\",\"count\":1}}",
                "{\"tool\":\"respond\",\"arguments\":{\"text\":\"All sorted.\"}}");
        AgentContext ctx = AgentContext.builder("Sean", world).owner(true).containers(containers).build();

        AgentResponse r = loop(backend).run("Put 3 diamonds in the chest, then take one back.", ctx);

        assertEquals("All sorted.", r.text());
        assertEquals(List.of("open_container", "put_in_container", "take_from_container"),
                r.toolCalls().stream().map(t -> t.call().name()).toList());
        assertEquals(2, world.items.get("diamond"), "3 removed then 1 added back");
        assertEquals(2, containers.view("chest").get().get("diamond"));
        assertFalse(r.limitExceeded());
    }

    @Test
    void searchForBlockFindsAndWalks() throws Exception {
        TestWorld world = new TestWorld();
        world.nearestBlock = java.util.Optional.of(new BlockInfo("iron_ore", 5, 63, 8, 6.4));
        ScriptedBackend backend = new ScriptedBackend(
                "{\"tool\":\"search_for_block\",\"arguments\":{\"block\":\"iron_ore\",\"range\":32}}",
                "{\"tool\":\"respond\",\"arguments\":{\"text\":\"Found it, on my way!\"}}");

        AgentResponse r = loop(backend).run("Search for iron ore and go to it.", context(world));

        assertEquals("Found it, on my way!", r.text());
        assertEquals(1, world.goToCalls, "search_for_block must navigate to the block");
        assertEquals("iron_ore", r.toolCalls().get(0).result().data().get("block"));
    }

    @Test
    void searchForEntityFindsAndWalks() throws Exception {
        TestWorld world = new TestWorld();
        world.entities = List.of(new net.clankerjockey.core.world.EntityInfo("cow", 4.0, 4, 64, 4, false, "front"));
        ScriptedBackend backend = new ScriptedBackend(
                "{\"tool\":\"search_for_entity\",\"arguments\":{\"type\":\"cow\",\"range\":32}}",
                "{\"tool\":\"respond\",\"arguments\":{\"text\":\"There's a cow over there.\"}}");

        AgentResponse r = loop(backend).run("Find a cow.", context(world));

        assertEquals("There's a cow over there.", r.text());
        assertEquals(1, world.goToCalls);
        assertEquals("cow", r.toolCalls().get(0).result().data().get("type"));
    }

    @Test
    void getVisibleBlocksListsSurroundings() throws Exception {
        TestWorld world = new TestWorld();
        world.visibleBlocks = List.of("grass_block", "oak_log", "stone");
        ScriptedBackend backend = new ScriptedBackend(
                "{\"tool\":\"get_visible_blocks\",\"arguments\":{\"radius\":8}}",
                "{\"tool\":\"respond\",\"arguments\":{\"text\":\"Mostly grass and trees here.\"}}");

        AgentResponse r = loop(backend).run("What's around me?", context(world));

        assertEquals("Mostly grass and trees here.", r.text());
        assertEquals(List.of("grass_block", "oak_log", "stone"),
                r.toolCalls().get(0).result().data().get("blocks"));
    }
}
