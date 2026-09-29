package dev.mcai.assistant;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static dev.mcai.assistant.AssistantService.Retrieval.*;

final class AiCommands {
    private static final String PREFIX = "[AI] ";
    private static final int CHAT_CHUNK_CODE_POINTS = 220;

    private AiCommands() {
    }

    static void register(AssistantService service) {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(commandTree(service));

            dispatcher.register(Commands.literal("aiadmin")
                    .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_MODERATOR))
                    .then(Commands.literal("status").executes(context -> {
                        context.getSource().sendSuccess(() -> Component.literal(PREFIX + service.status()), false);
                        return Command.SINGLE_SUCCESS;
                    }))
                    .then(Commands.literal("reload").executes(context -> {
                        String result = service.reload();
                        context.getSource().sendSuccess(() -> Component.literal(PREFIX + result), false);
                        return Command.SINGLE_SUCCESS;
                    }))
                    .then(Commands.literal("enable").executes(context -> {
                        service.setEnabled(true);
                        context.getSource().sendSuccess(() -> Component.literal(PREFIX + "AI 功能已启用。"), false);
                        return Command.SINGLE_SUCCESS;
                    }))
                    .then(Commands.literal("disable").executes(context -> {
                        service.setEnabled(false);
                        context.getSource().sendSuccess(() -> Component.literal(PREFIX + "AI 功能已禁用。"), false);
                        return Command.SINGLE_SUCCESS;
                    }))
                    .then(Commands.literal("clear").executes(context -> {
                        int count = service.clearAll();
                        context.getSource().sendSuccess(
                                () -> Component.literal(PREFIX + "已清除 " + count + " 个临时对话上下文。"), false);
                        return Command.SINGLE_SUCCESS;
                    })));
        });
    }

    static LiteralArgumentBuilder<CommandSourceStack> commandTree(AssistantService service) {
        var root = Commands.literal("ai")
                .executes(context -> help(context.getSource(), service))
                .then(Commands.literal("help").executes(context -> help(context.getSource(), service)))
                .then(Commands.literal("clear").executes(context -> clear(context, service)))
                .then(settingsCommands(service))
                .then(questionCommands(Commands.literal("private"), service, true))
                .then(questionCommands(Commands.literal("public"), service, false));
        return questionCommands(root, service, null);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> questionCommands(
            LiteralArgumentBuilder<CommandSourceStack> node, AssistantService service, Boolean visibility) {
        return node.then(Commands.literal("search")
                        .then(Commands.argument("question", StringArgumentType.greedyString())
                                .executes(context -> ask(context, service, WEB, visibility))))
                .then(Commands.literal("wiki")
                        .then(Commands.argument("question", StringArgumentType.greedyString())
                                .executes(context -> ask(context, service, WIKI, visibility))))
                .then(Commands.literal("plain")
                        .then(Commands.argument("question", StringArgumentType.greedyString())
                                .executes(context -> ask(context, service, NONE, visibility, ""))))
                .then(personaCommands(service, visibility))
                .then(Commands.argument("question", StringArgumentType.greedyString())
                        .executes(context -> ask(context, service, NONE, visibility)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> settingsCommands(AssistantService service) {
        return Commands.literal("settings")
                .executes(context -> settingsHelp(context, service))
                .then(Commands.literal("role")
                        .then(Commands.argument("personaId", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    service.personaList().stream().map(PersonaRegistry.Card::id)
                                            .filter(id -> id.startsWith(builder.getRemaining())).forEach(builder::suggest);
                                    return builder.buildFuture();
                                })
                                .executes(context -> updateSettings(context, service, id -> service.setDefaultPersona(id,
                                        StringArgumentType.getString(context, "personaId"))))))
                .then(Commands.literal("ordinary").executes(context -> updateSettings(context, service,
                        id -> service.setDefaultPersona(id, ""))))
                .then(Commands.literal("private").executes(context -> updateSettings(context, service,
                        id -> service.setDefaultVisibility(id, true))))
                .then(Commands.literal("public").executes(context -> updateSettings(context, service,
                        id -> service.setDefaultVisibility(id, false))))
                .then(Commands.literal("reset").executes(context -> updateSettings(context, service, service::resetPreferences)));
    }

    private static int settingsHelp(CommandContext<CommandSourceStack> context, AssistantService service)
            throws CommandSyntaxException {
        var player = context.getSource().getPlayerOrException();
        player.sendSystemMessage(Component.literal(PREFIX + service.preferenceSummary(player.getUUID())));
        player.sendSystemMessage(Component.literal(PREFIX + "/ai settings role <id> | ordinary（取消默认角色）"
                + " | private | public | reset（普通聊天、全服公开）。设置重启后保留。"));
        return Command.SINGLE_SUCCESS;
    }

    private static int updateSettings(CommandContext<CommandSourceStack> context, AssistantService service,
                                      java.util.function.Function<UUID, AssistantService.Submission> update)
            throws CommandSyntaxException {
        var player = context.getSource().getPlayerOrException();
        var result = update.apply(player.getUUID());
        player.sendSystemMessage(Component.literal(PREFIX + result.message()));
        return result.accepted() ? Command.SINGLE_SUCCESS : 0;
    }

    private static int ask(CommandContext<CommandSourceStack> context, AssistantService service,
                           AssistantService.Retrieval mode, Boolean privateReply) throws CommandSyntaxException {
        return ask(context, service, mode, privateReply, null);
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> personaCommands(
            AssistantService service, boolean privateReply) {
        return personaCommands(service, Boolean.valueOf(privateReply));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> personaCommands(
            AssistantService service, Boolean privateReply) {
        return Commands.literal("persona")
                .executes(context -> personaHelp(context.getSource(), service))
                .then(Commands.literal("list").executes(context -> personaHelp(context.getSource(), service)))
                .then(Commands.argument("personaId", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            for (var card : service.personaList()) {
                                if (card.id().startsWith(builder.getRemaining())) {
                                    builder.suggest(card.id());
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(context -> personaHelp(context.getSource(), service))
                        .then(Commands.argument("question", StringArgumentType.greedyString())
                                .executes(context -> ask(context, service, NONE, privateReply,
                                        StringArgumentType.getString(context, "personaId")))));
    }

    private static int personaHelp(CommandSourceStack source, AssistantService service) {
        String available = service.personaList().stream().map(card -> card.id() + "（" + card.displayName() + "）")
                .collect(java.util.stream.Collectors.joining("、"));
        source.sendSuccess(() -> Component.literal(PREFIX + "角色：" + (available.isEmpty() ? "暂无可用角色" : available)
                + "。用法：/ai persona <id> <内容>；私聊：/ai private persona <id> <内容>。"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int ask(CommandContext<CommandSourceStack> context, AssistantService service,
                           AssistantService.Retrieval mode, Boolean privateOverride, String personaId) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        UUID playerId = player.getUUID();
        MinecraftServer server = context.getSource().getServer();
        String question = StringArgumentType.getString(context, "question");
        AssistantService.PlayerRequest request = service.resolveRequest(
                playerId, player.getName().getString(), question, mode, privateOverride, personaId);
        boolean privateReply = request.privateReply();
        String notice = service.takePrivacyNotice(playerId);
        if (!notice.isEmpty()) {
            player.sendSystemMessage(Component.literal(PREFIX + notice));
        }
        AssistantService.Submission submission = service.submit(request,
                outcome -> {
                    if (!service.isClosed()) {
                        server.execute(() -> {
                            if (service.canDeliver(outcome)) {
                                deliver(server, playerId, privateReply, question, outcome);
                            }
                        });
                    }
                });
        if (!submission.accepted()) {
            player.sendSystemMessage(Component.literal(PREFIX + submission.message()));
            return 0;
        }
        player.sendSystemMessage(Component.literal(PREFIX + (privateReply ? "（仅本人可见）" : "（全服公开）") + switch (mode) {
            case WIKI -> "正在检索 Wiki 并思考……";
            case WEB -> "正在搜索并思考……";
            case NONE -> "正在思考……";
        }));
        return Command.SINGLE_SUCCESS;
    }

    private static int clear(CommandContext<CommandSourceStack> context, AssistantService service)
            throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        service.clear(player.getUUID());
        player.sendSystemMessage(Component.literal(PREFIX + "你的临时对话上下文已清除。"));
        return Command.SINGLE_SUCCESS;
    }

    private static int help(CommandSourceStack source, AssistantService service) {
        source.sendSuccess(() -> Component.literal(PREFIX
                + "/ai <内容>（使用个人默认设置） | /ai private <内容> | /ai public <内容> | /ai clear"), false);
        source.sendSuccess(() -> Component.literal(PREFIX + "/ai settings 设置默认角色和可见范围 | /ai plain <内容> 本次普通聊天"
                + " | /ai search <问题> | /ai wiki <问题>"), false);
        source.sendSuccess(() -> Component.literal(PREFIX + "私聊检索：/ai private search <问题> | /ai private wiki <问题>"), false);
        source.sendSuccess(() -> Component.literal(PREFIX + "角色：/ai persona list | /ai persona <id> <内容> | /ai private persona <id> <内容>"), false);
        source.sendSuccess(() -> Component.literal(PREFIX + service.helpSummary()), false);
        return Command.SINGLE_SUCCESS;
    }

    private static void deliver(MinecraftServer server, UUID playerId, boolean privateReply,
                                String question, AssistantService.Outcome outcome) {
        ServerPlayer requester = server.getPlayerList().getPlayer(playerId);
        if (requester == null) {
            return;
        }
        if (!outcome.success()) {
            requester.sendSystemMessage(Component.literal(PREFIX + outcome.text()));
            return;
        }
        send(server, requester, privateReply, outcome.persona() == null
                ? answerTitle(requester.getName().getString(), privateReply)
                : personaTitle(outcome.persona(), requester.getName().getString(), privateReply));
        send(server, requester, privateReply, questionLine(question));
        for (String chunk : splitForChat(outcome.text())) {
            send(server, requester, privateReply, answerChunk(chunk));
        }
        if (!outcome.sources().isEmpty()) {
            send(server, requester, privateReply, sourceLinks(outcome.sources()));
        }
        send(server, requester, privateReply, answerSeparator());
    }

    private static void send(MinecraftServer server, ServerPlayer requester, boolean privateReply,
                             Component message) {
        if (privateReply) {
            requester.sendSystemMessage(message);
            return;
        }
        for (ServerPlayer onlinePlayer : server.getPlayerList().getPlayers()) {
            onlinePlayer.sendSystemMessage(message);
        }
    }

    static Component answerTitle(String playerName, boolean privateReply) {
        String label = privateReply ? "AI 私人回答" : "AI 回答";
        return Component.literal("━━━━ " + label + " · " + playerName + " ━━━━")
                .withStyle(style -> style.withColor(ChatFormatting.GOLD).withBold(true));
    }

    static Component personaTitle(AssistantService.PersonaTag persona, String playerName, boolean privateReply) {
        return Component.literal("━━━━ [" + persona.label() + "] " + (privateReply ? "私人回答" : "回答")
                + " · " + playerName + " ━━━━")
                .withStyle(style -> style.withColor(ChatFormatting.GOLD).withBold(true));
    }

    static Component questionLine(String question) {
        return Component.literal("问题：")
                .withStyle(style -> style.withColor(ChatFormatting.GOLD).withBold(true))
                .append(Component.literal(question)
                        .withStyle(style -> style.withColor(ChatFormatting.WHITE)));
    }

    static Component answerChunk(String chunk) {
        return Component.literal("▍ ")
                .withStyle(style -> style.withColor(ChatFormatting.AQUA).withBold(true))
                .append(Component.literal(safeChatText(chunk))
                        .withStyle(style -> style.withColor(ChatFormatting.WHITE)));
    }

    static String safeChatText(String text) {
        return text.replaceAll("§.", "").replaceAll("[\\p{Cc}\\p{Cf}§]", "");
    }

    static Component answerSeparator() {
        return Component.literal("━━━━━━━━━━━━━━━━━━━━")
                .withStyle(style -> style.withColor(ChatFormatting.DARK_GRAY));
    }

    static Component sourceLinks(List<URI> sources) {
        List<URI> urls = SourceUrls.validated(sources.stream().map(URI::toString).toList());
        MutableComponent line = Component.literal("▍ 来源：")
                .withStyle(style -> style.withColor(ChatFormatting.AQUA).withBold(true));
        for (int index = 0; index < urls.size(); index++) {
            URI url = urls.get(index);
            line.append(" ");
            line.append(Component.literal("[" + (index + 1) + "]").withStyle(style -> style
                    .withColor(ChatFormatting.AQUA)
                    .withUnderlined(true)
                    .withClickEvent(new ClickEvent.OpenUrl(url))
                    .withHoverEvent(new HoverEvent.ShowText(Component.literal(url.toString())))));
        }
        return line;
    }

    static List<String> splitForChat(String answer) {
        String normalized = answer.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (normalized.isEmpty()) {
            return List.of("（模型未返回文本）");
        }
        List<String> chunks = new ArrayList<>();
        for (String paragraph : normalized.split("\\n+")) {
            String remaining = paragraph.strip();
            while (remaining.codePointCount(0, remaining.length()) > CHAT_CHUNK_CODE_POINTS) {
                int end = remaining.offsetByCodePoints(0, CHAT_CHUNK_CODE_POINTS);
                int whitespace = remaining.lastIndexOf(' ', end);
                if (whitespace > end - 40) {
                    end = whitespace;
                }
                chunks.add(remaining.substring(0, end).strip());
                remaining = remaining.substring(end).strip();
            }
            if (!remaining.isEmpty()) {
                chunks.add(remaining);
            }
        }
        return chunks.isEmpty() ? List.of("（模型未返回文本）") : List.copyOf(chunks);
    }
}
