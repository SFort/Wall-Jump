package genandnic.walljump.mixin.client;

import com.mojang.authlib.GameProfile;
import genandnic.walljump.WallJump;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.network.ClientSidePacketRegistry;
import net.minecraft.block.BlockState;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.math.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashSet;
import java.util.Set;

@Mixin(ClientPlayerEntity.class)
public abstract class ClientPlayerEntityWallJumpMixin extends AbstractClientPlayerEntity {

    @Shadow public abstract boolean isRiding();
    @Shadow public abstract float getYaw(float tickDelta);
    @Shadow public Input input;

    public int ticksWallClinged;
    private int ticksKeyDown;
    private double clingX;
    private double clingZ;
    private double lastJumpY = Double.MAX_VALUE;
    private Set<Direction> walls = new HashSet<>();
    private Set<Direction> staleWalls = new HashSet<>();


    public ClientPlayerEntityWallJumpMixin(ClientWorld world, GameProfile profile) {
        super(world, profile);
    }


    @Inject(method = "tickMovement", at = @At("TAIL"))
    private void wallJumpTickMovement(CallbackInfo ci) {
        this.doWallJump();
    }


    private void doWallJump() {
        if(this.onGround
                || this.getAbilities().flying
                || !this.world.getFluidState(this.getBlockPos()).isEmpty()
                || this.isRiding()
        ) {
            this.ticksWallClinged = 0;
            this.clingX = Double.NaN;
            this.clingZ = Double.NaN;
            this.lastJumpY = Double.MAX_VALUE;
            this.staleWalls.clear();

            return;
        }

        this.updateWalls();
        this.ticksKeyDown = input.sneaking ? this.ticksKeyDown + 1 : 0;

        if(this.ticksWallClinged < 1) {

            if (this.ticksKeyDown > 0
                    && this.ticksKeyDown < 4
                    && !this.walls.isEmpty()
                    && this.canWallCling()
            ) {

                this.limbDistance = 2.5F;
                this.lastLimbDistance = 2.5F;
                this.ticksWallClinged = 1;
                this.clingX = this.getX();
                this.clingZ = this.getZ();

                this.playHitSound(this.getWallPos());
            }

            return;
        }

        if(!input.sneaking
                || this.onGround
                || !this.world.getFluidState(this.getBlockPos()).isEmpty()
                || this.walls.isEmpty()
                || this.getHungerManager().getFoodLevel() < 1
        ) {

            this.ticksWallClinged = 0;

            if((this.forwardSpeed != 0 || this.sidewaysSpeed != 0)
                    && !this.onGround
                    && !this.walls.isEmpty()
            ) {

                this.fallDistance = 0.0F;

                PacketByteBuf passedData = new PacketByteBuf(Unpooled.buffer());
                passedData.writeBoolean(true);
                ClientSidePacketRegistry.INSTANCE.sendToServer(WallJump.WALL_JUMP_PACKET_ID, passedData);

                this.wallJump(0.55F);
                this.staleWalls = new HashSet<>(this.walls);
            }

            return;
        }
        this.setPos(this.clingX, this.getY(), this.clingZ);
        double motionY = this.getVelocity().getY();
        if(motionY > 0.0) {
            motionY = 0.0;
        } else if(motionY < -0.6) {
            motionY = motionY + 0.2;
        } else if(this.ticksWallClinged++ > 15) {
            motionY = -0.1;
        } else {
            motionY = 0.0;
        }

        this.setVelocity(0.0, motionY, 0.0);
    }


    private boolean canWallCling() {
        if(this.isClimbing() || this.getVelocity().getY() > 0.1 || this.getHungerManager().getFoodLevel() < 1)
            return false;
        if(!this.world.isSpaceEmpty(this.getBoundingBox().offset(0, -0.8, 0)))
            return false;
        if(this.getY() < this.lastJumpY - 1)
            return true;

        return !this.staleWalls.containsAll(this.walls);
    }


    private void updateWalls() {

        Box box = new Box(
                this.getX() - 0.001,
                this.getY(),
                this.getZ() - 0.001,
                this.getX() + 0.001,
                this.getY() + this.getStandingEyeHeight(),
                this.getZ() + 0.001
        );

        double dist = (this.getWidth() / 2) + (this.ticksWallClinged > 0 ? 0.1 : 0.06);

        Box[] axes = {
                box.stretch(0, 0, dist),
                box.stretch(-dist, 0, 0),
                box.stretch(0, 0, -dist),
                box.stretch(dist, 0, 0)
        };

        int i = 0;
        Direction direction;
        this.walls = new HashSet<>();

        for (Box axis : axes) {
            direction = Direction.fromHorizontal(i++);
            if(!this.world.isSpaceEmpty(axis)) {
               this.walls.add(direction);
               this.horizontalCollision = true;
            }
        }
    }


    private Direction getClingDirection() {

        return this.walls.isEmpty() ? Direction.UP : this.walls.iterator().next();
    }


    private BlockPos getWallPos() {

        BlockPos clingPos = this.getBlockPos().offset(this.getClingDirection());
        return this.world.getBlockState(clingPos).getMaterial().isSolid() ? clingPos : clingPos.offset(Direction.UP);
    }


    private void wallJump(float up) {

        float strafe = Math.signum(this.sidewaysSpeed) * up * up;
        float forward = Math.signum(this.forwardSpeed) * up * up;

        float f = 1.0F / MathHelper.sqrt(strafe * strafe + up * up + forward * forward);
        strafe = strafe * f;
        forward = forward * f;

        float f1 = MathHelper.sin(this.getHeadYaw() * 0.017453292F) * 0.45F;
        float f2 = MathHelper.cos(this.getHeadYaw() * 0.017453292F) * 0.45F;

        int jumpBoostLevel = 0;
        StatusEffectInstance jumpBoostEffect = this.getStatusEffect(StatusEffects.JUMP_BOOST);
        if(jumpBoostEffect != null) jumpBoostLevel = jumpBoostEffect.getAmplifier() + 1;

        Vec3d motion = this.getVelocity();
        this.setVelocity(
                motion.getX() + (strafe * f2 - forward * f1),
                up + (jumpBoostLevel * 0.125),
                motion.getZ() + (forward * f2 + strafe * f1)
        );

        this.lastJumpY = this.getY();
        this.playBreakSound(this.getWallPos());
    }


    private void playHitSound(BlockPos blockPos) {

        BlockState blockState = this.world.getBlockState(blockPos);
        BlockSoundGroup soundType = blockState.getBlock().getSoundGroup(blockState);
        this.playSound(soundType.getHitSound(), soundType.getVolume() * 0.25F, soundType.getPitch());
    }

    private void playBreakSound(BlockPos blockPos) {

        BlockState blockState = this.world.getBlockState(blockPos);
        BlockSoundGroup soundType = blockState.getBlock().getSoundGroup(blockState);
        this.playSound(soundType.getFallSound(), soundType.getVolume() * 0.5F, soundType.getPitch());
    }
}
