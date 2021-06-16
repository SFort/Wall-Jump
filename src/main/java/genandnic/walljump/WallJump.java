package genandnic.walljump;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.network.ServerSidePacketRegistry;
import net.minecraft.util.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


public class WallJump implements ModInitializer {
	public static final Logger LOGGER = LogManager.getLogger("WallJump");

	public static final Identifier WALL_JUMP_PACKET_ID = new Identifier("walljump", "walljump");

	@Override
	public void onInitialize() {
		ServerSidePacketRegistry.INSTANCE.register(WALL_JUMP_PACKET_ID, ((packetContext, packetByteBuf) -> {
			boolean didWallJump = packetByteBuf.readBoolean();
			packetContext.getTaskQueue().execute(() -> {
				if(didWallJump)
					packetContext.getPlayer().addExhaustion(0.8F);
			});
		}));
		LOGGER.info("[Wall Jump] initialized!");
	}
}
