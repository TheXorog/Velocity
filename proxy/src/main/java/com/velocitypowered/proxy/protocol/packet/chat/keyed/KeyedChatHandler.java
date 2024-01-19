/*
 * Copyright (C) 2022-2023 Velocity Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.velocitypowered.proxy.protocol.packet.chat.keyed;

import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.crypto.IdentifiedKey;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.protocol.MinecraftPacket;
import com.velocitypowered.proxy.protocol.packet.chat.ChatQueue;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static net.kyori.adventure.text.format.NamedTextColor.*;
import static net.kyori.adventure.text.format.TextDecoration.*;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.event.ClickEvent;

public class KeyedChatHandler implements
    com.velocitypowered.proxy.protocol.packet.chat.ChatHandler<KeyedPlayerChatPacket> {

  private static final Logger logger = LogManager.getLogger(KeyedChatHandler.class);

  private final VelocityServer server;
  private final ConnectedPlayer player;

  public KeyedChatHandler(VelocityServer server, ConnectedPlayer player) {
    this.server = server;
    this.player = player;
  }

  @Override
  public Class<KeyedPlayerChatPacket> packetClass() {
    return KeyedPlayerChatPacket.class;
  }

  public static void invalidCancel(Logger logger, ConnectedPlayer player) {
    player.sendMessage(Component.text("Please download & install a mod that prevents signed chat messages.\n\n", DARK_RED)
            .append(Component.text("Signing puts your account at risk of being incorrectly banned, the implementation allows for third parties to omit messages and may paint you as a bad actor.\n\n", RED)
            .append(Component.text("If you want to learn more, i recommend this video by Aizistral which goes into detail about this system: ", GRAY))
            .append(Component.text("https://www.youtube.com/watch?v=hYAUEMlugyw\n\n", YELLOW)
                    .clickEvent(ClickEvent.clickEvent(ClickEvent.Action.OPEN_URL,
                            "https://www.youtube.com/watch?v=hYAUEMlugyw"))
                    .hoverEvent(Component.text("https://www.youtube.com/watch?v=hYAUEMlugyw").asHoverEvent()))
            .append(Component.text("Recommended mod: ", GRAY))
            .append(Component.text("NoChatReports (Fabric & Forge)", YELLOW)
                    .clickEvent(ClickEvent.clickEvent(ClickEvent.Action.OPEN_URL,
                    "https://modrinth.com/mod/no-chat-reports"))
                    .hoverEvent(Component.text("https://modrinth.com/mod/no-chat-reports").asHoverEvent()))));
  }

  public static void invalidChange(Logger logger, ConnectedPlayer player) {
    invalidCancel(logger, player);
  }

  @Override
  public void handlePlayerChatInternal(KeyedPlayerChatPacket packet) {
    ChatQueue chatQueue = this.player.getChatQueue();
    EventManager eventManager = this.server.getEventManager();
    PlayerChatEvent toSend = new PlayerChatEvent(player, packet.getMessage());
    CompletableFuture<PlayerChatEvent> future = eventManager.fire(toSend);

    CompletableFuture<MinecraftPacket> chatFuture;
    IdentifiedKey playerKey = this.player.getIdentifiedKey();

    if (playerKey != null && !packet.isUnsigned()) {
      // 1.19->1.19.2 signed version
      chatFuture = future.thenApply(handleOldSignedChat(packet));
    } else {
      // 1.19->1.19.2 unsigned version
      chatFuture = future.thenApply(pme -> {
        PlayerChatEvent.ChatResult chatResult = pme.getResult();
        if (!chatResult.isAllowed()) {
          return null;
        }

        return player.getChatBuilderFactory().builder()
            .message(chatResult.getMessage().orElse(packet.getMessage()))
            .setTimestamp(packet.getExpiry()).toServer();
      });
    }
    chatQueue.queuePacket(
        chatFuture.exceptionally((ex) -> {
          logger.error("Exception while handling player chat for {}", player, ex);
          return null;
        }),
        packet.getExpiry()
    );
  }

  private Function<PlayerChatEvent, MinecraftPacket> handleOldSignedChat(KeyedPlayerChatPacket packet) {
    IdentifiedKey playerKey = this.player.getIdentifiedKey();
    assert playerKey != null;
    return pme -> {
      PlayerChatEvent.ChatResult chatResult = pme.getResult();
      if (!chatResult.isAllowed()) {
        if (playerKey.getKeyRevision().noLessThan(IdentifiedKey.Revision.LINKED_V2)) {
          // Bad, very bad.
          invalidCancel(logger, player);
        }
        return null;
      }

      if (chatResult.getMessage().map(str -> !str.equals(packet.getMessage())).orElse(false)) {
        if (playerKey.getKeyRevision().noLessThan(IdentifiedKey.Revision.LINKED_V2)) {
          // Bad, very bad.
          invalidChange(logger, player);
        } else {
          logger.warn("A plugin changed a signed chat message. The server may not accept it.");
          return player.getChatBuilderFactory().builder()
              .message(chatResult.getMessage().get() /* always present at this point */)
              .setTimestamp(packet.getExpiry())
              .toServer();
        }
      }
      return packet;
    };
  }
}
