package com.simibubi.create.modules.contraptions.receivers.constructs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllTileEntities;
import com.simibubi.create.modules.contraptions.base.KineticTileEntity;
import com.simibubi.create.modules.contraptions.receivers.constructs.MechanicalPistonBlock.PistonState;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.state.properties.BlockStateProperties;
import net.minecraft.tileentity.ITickableTileEntity;
import net.minecraft.util.Direction;
import net.minecraft.util.Direction.Axis;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.gen.feature.template.Template.BlockInfo;

public class MechanicalPistonTileEntity extends KineticTileEntity implements ITickableTileEntity {

	protected static List<MechanicalPistonTileEntity> movingPistons = new ArrayList<>();

	protected Construct movingConstruct;
	protected float offset;
	protected boolean running;
	protected boolean assembleNextTick;
	protected boolean hadCollisionWithOtherPiston;

	public MechanicalPistonTileEntity() {
		super(AllTileEntities.MECHANICAL_PISTON.type);
	}

	@Override
	public boolean hasFastRenderer() {
		return true;
	}

	@Override
	public void onSpeedChanged() {
		super.onSpeedChanged();
		assembleNextTick = true;
	}

	@Override
	public AxisAlignedBB getRenderBoundingBox() {
		return INFINITE_EXTENT_AABB;
	}

	@Override
	public CompoundNBT write(CompoundNBT tag) {
		tag.putBoolean("Running", running);
		tag.putFloat("Offset", offset);
		if (running)
			tag.put("Construct", movingConstruct.writeNBT());

		return super.write(tag);
	}

	@Override
	public void read(CompoundNBT tag) {
		running = tag.getBoolean("Running");
		offset = tag.getFloat("Offset");
		if (running)
			movingConstruct = Construct.fromNBT(tag.getCompound("Construct"));

		super.read(tag);
	}

	protected void onBlockVisited(float newOffset) {
		Direction direction = getBlockState().get(BlockStateProperties.FACING);

		for (BlockInfo block : movingConstruct.actors) {
			IHaveMovementBehavior actor = (IHaveMovementBehavior) block.state.getBlock();
			actor.visitPosition(world, block.pos.offset(direction, getModulatedOffset(newOffset)), block.state,
					getMovementSpeed() > 0 ? direction : direction.getOpposite(), this);
		}

	}

	public void assembleConstruct() {
		Direction direction = getBlockState().get(BlockStateProperties.FACING);

		// Collect Construct
		movingConstruct = getMovementSpeed() < 0 ? Construct.getAttachedForPulling(getWorld(), getPos(), direction)
				: Construct.getAttachedForPushing(getWorld(), getPos(), direction);
		if (movingConstruct == null)
			return;

		// Check if not at limit already
		float resultingOffset = movingConstruct.initialExtensionProgress + getMovementSpeed();
		if (resultingOffset <= 0 || resultingOffset >= movingConstruct.extensionLength) {
			movingConstruct = null;
			return;
		}
		if (hasBlockCollisions(resultingOffset + .5f)) {
			movingConstruct = null;
			return;
		}

		// Run
		running = true;
		offset = movingConstruct.initialExtensionProgress;
		movingPistons.add(this);

		sendData();
		getWorld().setBlockState(pos, getBlockState().with(MechanicalPistonBlock.STATE, PistonState.MOVING), 66);
		for (BlockInfo block : movingConstruct.blocks.values()) {
			BlockPos startPos = block.pos.offset(direction, movingConstruct.initialExtensionProgress);
			if (startPos.equals(pos))
				continue;
			getWorld().setBlockState(startPos, Blocks.AIR.getDefaultState(), 67);
		}
		
		onBlockVisited(offset);
	}

	public void disassembleConstruct() {
		if (!running)
			return;

		Direction direction = getBlockState().get(BlockStateProperties.FACING);
		getWorld().setBlockState(pos, getBlockState().with(MechanicalPistonBlock.STATE, PistonState.EXTENDED), 3);

		for (BlockInfo block : movingConstruct.blocks.values()) {
			BlockPos targetPos = block.pos.offset(direction, getModulatedOffset(offset));
			BlockState state = block.state;
			if (targetPos.equals(pos)) {
				if (!AllBlocks.PISTON_POLE.typeOf(state))
					getWorld().setBlockState(pos,
							getBlockState().with(MechanicalPistonBlock.STATE, PistonState.RETRACTED), 3);
				continue;
			}
			for (Direction face : Direction.values())
				state = state.updatePostPlacement(face, world.getBlockState(targetPos.offset(face)), world, targetPos,
						targetPos.offset(face));

			world.destroyBlock(targetPos, world.getBlockState(targetPos).getCollisionShape(world, targetPos).isEmpty());
			getWorld().setBlockState(targetPos, state, 3);
		}

		running = false;
		movingPistons.remove(this);
		movingConstruct = null;
		sendData();
	}

	@Override
	public void tick() {
		if (!world.isRemote && assembleNextTick) {
			assembleNextTick = false;
			if (running) {
				if (speed == 0)
					disassembleConstruct();
				return;
			}
			assembleConstruct();
			return;
		}

		if (!running)
			return;

		float movementSpeed = getMovementSpeed();
		Direction movementDirection = getBlockState().get(BlockStateProperties.FACING);
		float newOffset = offset + movementSpeed;

		ConstructEntityHelper.moveEntities(this, movementSpeed, movementDirection, newOffset);

		if (world.isRemote) {
			offset = newOffset;
			return;
		}

		if (getModulatedOffset(newOffset) != getModulatedOffset(offset)) {
			onBlockVisited(newOffset);
		}

		float movement = .5f + (movementSpeed < 0 ? -1f : 0);
		if (getModulatedOffset(newOffset + movement) != getModulatedOffset(offset + movement)) {
			if (hasBlockCollisions(newOffset + movement)) {
				disassembleConstruct();
				if (hadCollisionWithOtherPiston)
					hadCollisionWithOtherPiston = false;
				else if (movementSpeed > 0)
					assembleNextTick = true;
				return;
			}
		}

		offset = newOffset;

		if (offset <= 0 || offset >= movingConstruct.extensionLength) {
			disassembleConstruct();
			return;
		}
	}

	private boolean hasBlockCollisions(float newOffset) {
		Direction movementDirection = getBlockState().get(BlockStateProperties.FACING);
		BlockPos relativePos = BlockPos.ZERO.offset(movementDirection, getModulatedOffset(newOffset));

		// Other moving Pistons
		int maxPossibleRange = Construct.MAX_EXTENSIONS + Construct.MAX_CHAINED_BLOCKS + Construct.MAX_CHAINED_CHASSIS;
		Iterator<MechanicalPistonTileEntity> iterator = movingPistons.iterator();
		while (iterator.hasNext()) {
			MechanicalPistonTileEntity otherPiston = iterator.next();

			if (otherPiston == this)
				continue;
			if (!otherPiston.running || otherPiston.movingConstruct == null) {
				iterator.remove();
				continue;
			}
			if (otherPiston.pos.manhattanDistance(pos) > maxPossibleRange * 2)
				continue;

			Direction otherMovementDirection = otherPiston.getBlockState().get(BlockStateProperties.FACING);
			BlockPos otherRelativePos = BlockPos.ZERO.offset(otherMovementDirection,
					getModulatedOffset(otherPiston.offset));

			for (AxisAlignedBB thisBB : Arrays.asList(movingConstruct.collisionBoxFront,
					movingConstruct.collisionBoxBack)) {
				for (AxisAlignedBB otherBB : Arrays.asList(otherPiston.movingConstruct.collisionBoxFront,
						otherPiston.movingConstruct.collisionBoxBack)) {

					if (thisBB == null || otherBB == null)
						continue;
					if (thisBB.offset(relativePos).intersects(otherBB.offset(otherRelativePos))) {
						hadCollisionWithOtherPiston = true;
						return true;
					}
					if (otherMovementDirection == movementDirection.getOpposite()) {
						if (thisBB.offset(relativePos.offset(movementDirection, getMovementSpeed() > 0 ? 1 : -1))
								.intersects(otherBB.offset(otherRelativePos))) {
							hadCollisionWithOtherPiston = true;
							return true;
						}
					}

				}
			}

		}

		if (!running)
			return false;

		// Other Blocks in world
		for (BlockPos pos : movingConstruct.getColliders(world,
				getMovementSpeed() > 0 ? movementDirection : movementDirection.getOpposite())) {
			BlockPos colliderPos = pos.add(relativePos);

			if (!world.isBlockPresent(colliderPos))
				return true;
			if (!world.getBlockState(colliderPos).getMaterial().isReplaceable()
					&& !world.getBlockState(colliderPos).getCollisionShape(world, colliderPos).isEmpty())
				return true;
		}

		return false;
	}

	private int getModulatedOffset(float offset) {
		return MathHelper.clamp((int) (offset + .5f), 0, movingConstruct.extensionLength);
	}

	public float getMovementSpeed() {
		Direction pistonDirection = getBlockState().get(BlockStateProperties.FACING);
		int movementModifier = pistonDirection.getAxisDirection().getOffset()
				* (pistonDirection.getAxis() == Axis.Z ? -1 : 1);
		return getSpeed() * -movementModifier / 1024f;
	}

	public Vec3d getConstructOffset(float partialTicks) {
		float interpolatedOffset = MathHelper.clamp(offset + (partialTicks - .5f) * getMovementSpeed(), 0,
				movingConstruct.extensionLength);
		return new Vec3d(getBlockState().get(BlockStateProperties.FACING).getDirectionVec()).scale(interpolatedOffset);
	}

}