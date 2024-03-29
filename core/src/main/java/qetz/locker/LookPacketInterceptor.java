package qetz.locker;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.PlayerInfoData;
import com.google.common.collect.Lists;
import jakarta.inject.Inject;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import qetz.locker.outfit.Outfit;
import qetz.locker.packet.WrappedPlayServerPlayerInfo;
import qetz.locker.packet.WrappedPlayServerScoreboardTeam;
import qetz.locker.packet.WrappedPlayServerScoreboardTeam.Mode;
import qetz.locker.packet.WrappedPlayServerSpawnNamedEntity;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

import static com.comphenix.protocol.PacketType.Play.Server.*;
import static qetz.locker.packet.WrappedPlayServerScoreboardTeam.Mode.*;

public final class LookPacketInterceptor extends PacketAdapter {
  private final PaperLocker locker;

  private static final Set<PacketType> listeningPackets = Set.of(
    PLAYER_INFO,
    NAMED_ENTITY_SPAWN,
    SCOREBOARD_TEAM
  );

  @Inject
  private LookPacketInterceptor(PaperLocker locker, Plugin plugin) {
    super(plugin, listeningPackets);
    this.locker = locker;
  }

  @Override
  public void onPacketSending(PacketEvent sending) {
    if (sending.getPacketType().equals(PLAYER_INFO)) {
      sending.setPacket(applyLook(
        sending.getPacket().shallowClone(),
        sending.getPlayer()
      ));
    } else if (sending.getPacketType().equals(NAMED_ENTITY_SPAWN)) {
      sending.setPacket(reviseSpawnPacket(
        sending.getPacket().shallowClone(),
        sending.getPlayer()
      ));
    } else if (sending.getPacketType().equals(SCOREBOARD_TEAM)) {
      sending.setPacket(adjustScoreboardTeam(
        sending.getPacket().shallowClone(),
        sending.getPlayer()
      ));
    }
  }

  private PacketContainer applyLook(PacketContainer packet, Player receiver) {
    var wrapper = WrappedPlayServerPlayerInfo.withPacket(packet);
    var edited = wrapper.data().stream()
      .map(original -> createPlayerData(original, receiver))
      .toList();
    wrapper.setData(edited);
    return wrapper.handle();
  }

  private PlayerInfoData createPlayerData(PlayerInfoData original, Player receiver) {
    var look = locker.findOrCreateByOriginal(
      Outfit.fromGameProfile(original.getProfile())
    );
    var outfit = look.chooseOutfit(receiver);

    return new PlayerInfoData(
      outfit.toGameProfile(),
      original.getLatency(),
      original.getGameMode(),
      original.getDisplayName()
    );
  }

  private static final String skinKey = "textures";

  private PacketContainer reviseSpawnPacket(
    PacketContainer packet,
    Player receiver
  ) {
    var wrapper = WrappedPlayServerSpawnNamedEntity.withPacket(packet);
    var look = locker.findById(wrapper.id()).orElseThrow();
    var outfit = look.chooseOutfit(receiver);
    wrapper.setId(outfit.id());
    return wrapper.handle();
  }

  private static final Set<Mode> updateModes = Set.of(
    TeamCreated,
    PlayersAdded,
    PlayersRemoved
  );

  // TODO: Required?
  private PacketContainer adjustScoreboardTeam(
    PacketContainer packet,
    Player receiver
  ) {
    var wrapper = WrappedPlayServerScoreboardTeam.withPacket(packet);
    if (updateModes.contains(wrapper.mode())) {
      wrapper.setPlayers(reviseTeamPlayers(wrapper.players(), receiver));
    }
    return wrapper.handle();
  }

  private Collection<String> reviseTeamPlayers(
    Collection<String> original,
    Player receiver
  ) {
    Collection<String> edited = Lists.newArrayList();
    for (var name : original) {
      var edit = findPlayerByName(name)
        .map(player -> locker.findById(player.getUniqueId())
          .orElseThrow()
          .chooseOutfit(receiver)
          .name()
        )
        .orElse(name);
      edited.add(edit);
    }
    return edited;
  }

  private Optional<Player> findPlayerByName(String name) {
    return Optional.ofNullable(Bukkit.getPlayer(name));
  }
}