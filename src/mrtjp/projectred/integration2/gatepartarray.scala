/*
 * Copyright (c) 2014.
 * Created by MrTJP.
 * All rights reserved.
 */
package mrtjp.projectred.integration2

import codechicken.lib.data.{MCDataInput, MCDataOutput}
import codechicken.lib.vec._
import codechicken.multipart.TMultiPart
import mrtjp.core.world.Messenger
import mrtjp.projectred.api.IConnectable
import mrtjp.projectred.core.libmc.PRLib
import mrtjp.projectred.core.{Configurator, TFaceOrient}
import mrtjp.projectred.transmission._
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.nbt.NBTTagCompound

trait TArrayGatePart extends RedstoneGatePart with IRedwirePart with TFaceRSPropagation
{
    def getLogicArray = getLogic[TArrayGateLogic[TArrayGatePart]]

    override def getSignal = getLogicArray.getSignal(toInternalMask(propagationMask))
    override def setSignal(signal:Int) = getLogicArray.setSignal(toInternalMask(propagationMask), signal)

    abstract override def updateAndPropagate(prev:TMultiPart, mode:Int)
    {
        val rd = sideDiff(prev)
        var uMask = 0
        for (r <- 0 until 4) if ((rd&1<<r) != 0)
        {
            val pMask = getLogicArray.propogationMask(toInternal(r))
            if (pMask > 0 && (pMask&uMask) != pMask)
            {
                propagationMask = toAbsoluteMask(getLogicArray.propogationMask(toInternal(r)))
                super.updateAndPropagate(prev, mode)
                uMask |= pMask
            }
        }
        if (uMask == 0) WirePropagator.addNeighborChange(new BlockCoord(tile))
        propagationMask = 0xF
    }

    def sideDiff(p:TMultiPart):Int =
    {
        if (!p.isInstanceOf[TFaceOrient] || p.tile == null) return 0xF
        val part = p.asInstanceOf[TFaceOrient]
        val here = new BlockCoord(tile)
        val there = new BlockCoord(part.tile)

        if (here == there && (side&6) != (part.side&6)) return 1<<Rotation.rotationTo(side, part.side)

        if (side != part.side) there.offset(side^1) //bring corner up to same plane
        here.sub(there)

        import codechicken.lib.vec.Rotation._
        (here.x, here.y, here.z) match
        {
            case ( 0, 1, 0) => 1<<rotationTo(side, 0)
            case ( 0,-1, 0) => 1<<rotationTo(side, 1)
            case ( 0, 0, 1) => 1<<rotationTo(side, 2)
            case ( 0, 0,-1) => 1<<rotationTo(side, 3)
            case ( 1, 0, 0) => 1<<rotationTo(side, 4)
            case (-1, 0, 0) => 1<<rotationTo(side, 5)
            case _ => throw new RuntimeException("Propagating to distant part from "+here+" to "+there+"!?")
        }
    }

    override def calculateSignal:Int =
    {
        if (getLogicArray.overrideSignal(toInternalMask(propagationMask)))
            return getLogicArray.calculateSignal(toInternalMask(propagationMask))

        WirePropagator.setDustProvidePower(false)
        WirePropagator.redwiresProvidePower = false
        var s = 0
        def raise(sig:Int){ if (sig > s) s = sig }

        for (r <- 0 until 4) if ((propagationMask&1<<r) != 0)
            if (maskConnectsCorner(r)) raise(calcCornerSignal(r))
            else if (maskConnectsStraight(r)) raise(calcStraightSignal(r))
            else if (maskConnectsInside(r)) raise(calcInternalSignal(r))
            else raise(calcMaxSignal(r, false, true))

        WirePropagator.setDustProvidePower(true)
        WirePropagator.redwiresProvidePower = true
        s
    }

    abstract override def onChange()
    {
        super.onChange()
        WirePropagator.propagateTo(this, IWirePart.RISING)
    }

    override def onSignalUpdate()
    {
        tile.markDirty()
        super.onChange()
        getLogicArray.onSignalUpdate()
    }

    abstract override def resolveSignal(part:Any, r:Int) = part match
    {
        case re:IRedwirePart if re.diminishOnSide(r) => re.getRedwireSignal(r)-1
        case _ => super.resolveSignal(part, r)
    }

    override def getRedwireSignal(r:Int) =
        getLogicArray.getSignal(getLogicArray.propogationMask(toInternal(r)))

    abstract override def canConnectRedstone(side:Int):Boolean =
    {
        if (super.canConnectRedstone(side)) return true
        getLogicArray.canConnectRedwire(this, toInternal(absoluteRot(side)))
    }

    def rsLevel(i:Int):Int =
        if (WirePropagator.redwiresProvidePower) (i+16)/17
        else 0

    abstract override def weakPowerLevel(side:Int):Int =
    {
        if ((side&6) == (this.side&6)) return 0
        val ir = toInternal(absoluteRot(side))
        if ((getLogicArray.redwireMask(shape)&1<<ir) != 0)
            return rsLevel(getLogicArray.getSignal(getLogicArray.propogationMask(ir)))
        super.weakPowerLevel(side)
    }

    override def diminishOnSide(r:Int) = (getLogicArray.redwireMask(shape)&1<<toInternal(r)) != 0

    abstract override def rotate()
    {
        val r = rotation
        setRotation((r+1)%4)
        val b = tile.canReplacePart(this, this)
        setRotation(r)
        if (b) super.rotate()
    }

    abstract override def preparePlacement(player:EntityPlayer, pos:BlockCoord, side:Int, meta:Int)
    {
        super.preparePlacement(player, pos, side, meta)
        if (getLogicArray.canCross)
        {
            val npart = PRLib.getMultiPart(player.worldObj, pos, this.side^1)
            npart match
            {
                case apart:TArrayGatePart => if (apart.subID == subID && (apart.rotation&1) == (rotation&1))
                    setRotation((rotation+1)%4)
                case _ =>
            }
        }
    }

    abstract override def occlusionTest(npart:TMultiPart) = npart match
    {
        case apart:TArrayGatePart if apart.getLogicArray.canCross =>
            if (apart.subID == subID && apart.side == (side^1) && (apart.rotation&1) != (rotation&1)) true
            else super.occlusionTest(npart)
        case _ => super.occlusionTest(npart)
    }
}

object IGateWireRenderConnect
{
    def getConnsAtHeight(gate:GatePart, h:Double) =
    {
        var conn = 0
        for (r <- 0 until 4) if (getConnHeight(gate, r) == h) conn |= 1<<r
        gate.toInternalMask(conn)
    }

    def getConnHeight(gate:GatePart, r:Int) = gate.getStraight(r) match
    {
        case g:GatePart => g.getLogic[Any] match
        {
            case logic:IGateWireRenderConnect =>
                val ir = g.toInternal(gate.rotFromStraight(r))
                if ((logic.renderConnectMask&1<<ir) != 0) logic.getHeight(ir)
                else -1.0D
            case _ => -1.0D
        }
        case _ => -1.0D
    }
}

trait IGateWireRenderConnect
{
    def renderConnectMask:Int
    def getHeight(r:Int):Double
}

trait TArrayGateLogic[T <: TArrayGatePart] extends RedstoneGateLogic[T]
{
    abstract override def canConnecetTo(gate:T, part:IConnectable, r:Int) = part match
    {
        case re:IRedwireEmitter if canConnectRedwire(gate, r) => true
        case _ => super.canConnecetTo(gate, part, r)
    }

    def canConnectRedwire(gate:T, r:Int):Boolean = canConnectRedwire(gate.shape, r)
    def canConnectRedwire(shape:Int, r:Int):Boolean = (redwireMask(shape)&1<<r) != 0

    def redwireMask(shape:Int):Int

    def propogationMask(r:Int):Int

    def getSignal(mask:Int):Int
    def setSignal(mask:Int, signal:Int)

    def overrideSignal(mask:Int) = false
    def calculateSignal(mask:Int) = 0

    def canCross = false

    def onSignalUpdate()
}

class ArrayGatePart extends RedstoneGatePart with TComplexGatePart with TArrayGatePart
{
    private var logic:ArrayGateLogic = null

    override def getLogic[T] = logic.asInstanceOf[T]

    override def assertLogic()
    {
        if (logic == null) logic = ArrayGateLogic.create(this, subID)
    }

    override def getType = "pr_agate"
}

object ArrayGatePart
{
    val oBoxes = Array.ofDim[Cuboid6](6, 2)
    val cBoxes = new Array[Cuboid6](6)

    oBoxes(0)(0) = new Cuboid6(1/8D, 0, 0, 7/8D, 6/8D, 1)
    oBoxes(0)(1) = new Cuboid6(0, 0, 1/8D, 1, 6/8D, 7/8D)
    cBoxes(0) = new Cuboid6(0, 0, 0, 1, 6/8D, 1)
    for (s <- 1 until 6)
    {
        val t = Rotation.sideRotations(s).at(Vector3.center)
        oBoxes(s)(0) = oBoxes(0)(0).copy.apply(t)
        oBoxes(s)(1) = oBoxes(0)(1).copy.apply(t)
        cBoxes(s) = cBoxes(0).copy.apply(t)
    }
}

object ArrayGateLogic
{
    import mrtjp.projectred.integration2.GateDefinition._
    def create(gate:ArrayGatePart, subID:Int) = subID match
    {
        case NullCell.ordinal => new NullCell(gate)
        case InvertCell.ordinal => new InvertCell(gate)
        case BufferCell.ordinal => new BufferCell(gate)
        case ANDCell.ordinal => new ANDCell(gate)
        case _ => null
    }

}

abstract class ArrayGateLogic(gate:ArrayGatePart) extends RedstoneGateLogic[ArrayGatePart] with TArrayGateLogic[ArrayGatePart] with TComlexGateLogic[ArrayGatePart]

abstract class ArrayGateLogicCrossing(gate:ArrayGatePart) extends ArrayGateLogic(gate) with IGateWireRenderConnect
{
    var signal1:Byte = 0
    var signal2:Byte = 0

    override def redwireMask(shape:Int) = 0xF
    override def propogationMask(r:Int) = if (r%2 == 0) 0x5 else 0xA
    override def inputMask(shape:Int) = 0xF
    override def outputMask(shape:Int) = 0xF

    override def renderConnectMask = 0xA
    override def getHeight(r:Int) = 10.0D

    override def getSignal(mask:Int) = (if (mask == 0x5) signal1 else signal2)&0xFF
    override def setSignal(mask:Int, signal:Int)
    {
        if (mask == 0x5) signal1 = signal.toByte else signal2 = signal.toByte
    }

    override def save(tag:NBTTagCompound)
    {
        super.save(tag)
        tag.setByte("s1", signal1)
        tag.setByte("s2", signal2)
    }

    override def load(tag:NBTTagCompound)
    {
        super.load(tag)
        signal1 = tag.getByte("s1")
        signal2 = tag.getByte("s2")
    }

    override def writeDesc(packet:MCDataOutput)
    {
        super.writeDesc(packet)
        packet.writeByte(signal1)
        packet.writeByte(signal2)
    }

    override def readDesc(packet:MCDataInput)
    {
        super.readDesc(packet)
        signal1 = packet.readByte()
        signal2 = packet.readByte()
    }

    override def read(packet:MCDataInput, key:Int) = key match
    {
        case 11 =>
            signal1 = packet.readByte()
            signal2 = packet.readByte()
            if (Configurator.staticGates) gate.tile.markRender()
        case _ =>
    }

    def sendSignalUpdate(){ gate.getWriteStreamOf(11).writeByte(signal1).writeByte(signal2) }

    override def onChange(gate:ArrayGatePart)
    {
        val oldSignal = (gate.state&1) != 0
        val newSignal = signal1 != 0

        if (oldSignal != newSignal)
        {
            gate.setState(gate.state&2|(if (newSignal) 1 else 0))
            gate.onInputChange()
            gate.scheduleTick(2)
        }
    }

    override def scheduledTick(gate:ArrayGatePart)
    {
        val input = (gate.state&1) != 0
        val oldOutput = (gate.state&2) != 0
        val newOutput = !input

        if (oldOutput != newOutput)
        {
            gate.setState(gate.state&1|(if (newOutput) 2 else 0))
            gate.onOutputChange(0)
            gate.onChange()
        }
    }

    override def getOcclusions(gate:ArrayGatePart) = ArrayGatePart.oBoxes(gate.side)
    override def getBounds(gate:ArrayGatePart) = ArrayGatePart.cBoxes(gate.side)

    override def onSignalUpdate(){ sendSignalUpdate() }

    override def overrideSignal(mask:Int) = if (mask == 0xA) powerUp else false

    override def calculateSignal(mask:Int) = 255

    def powerUp:Boolean
}

class NullCell(gate:ArrayGatePart) extends ArrayGateLogicCrossing(gate)
{
    override def canCross = true

    override def powerUp = false

    override def lightLevel = 0
}

class InvertCell(gate:ArrayGatePart) extends ArrayGateLogicCrossing(gate)
{
    override def powerUp = (gate.state&2) != 0
}

class BufferCell(gate:ArrayGatePart) extends ArrayGateLogicCrossing(gate)
{
    override def powerUp = (gate.state&2) == 0
}

class ANDCell(gate:ArrayGatePart) extends ArrayGateLogic(gate) with TSimpleRSGateLogic[ArrayGatePart] with IGateWireRenderConnect
{
    var signal:Byte = 0

    override def redwireMask(shape:Int) = 0xA
    override def propogationMask(r:Int) = if (r%2 == 1) 0xA else 0
    override def inputMask(shape:Int) = 4
    override def outputMask(shape:Int) = 1

    override def getSignal(mask:Int) = if (mask == 0xA) signal&0xFF else 0
    override def setSignal(mask:Int, sig:Int){ if (mask == 0xA) signal = sig.toByte }

    override def renderConnectMask = 0xA
    override def getHeight(r:Int) = 10.0D

    override def save(tag:NBTTagCompound)
    {
        super.save(tag)
        tag.setByte("signal", signal)
    }

    override def load(tag:NBTTagCompound)
    {
        super.load(tag)
        signal = tag.getByte("signal")
    }

    override def writeDesc(packet:MCDataOutput)
    {
        super.writeDesc(packet)
        packet.writeByte(signal)
    }

    override def readDesc(packet:MCDataInput)
    {
        super.readDesc(packet)
        signal = packet.readByte()
    }

    override def read(packet:MCDataInput, key:Int) = key match
    {
        case 11 =>
            signal = packet.readByte()
            if (Configurator.staticGates) gate.tile.markRender()
        case _ =>
    }

    def sendSignalUpdate(){ gate.getWriteStreamOf(11).writeByte(signal) }

    override def onSignalUpdate(){ sendSignalUpdate() }

    override def calcOutput(gate:ArrayGatePart, input:Int) = if (input == 4 && signal != 0) 1 else 0

    override def getOcclusions(gate:ArrayGatePart) = ArrayGatePart.oBoxes(gate.side)
    override def getBounds(gate:ArrayGatePart) = ArrayGatePart.cBoxes(gate.side)
}