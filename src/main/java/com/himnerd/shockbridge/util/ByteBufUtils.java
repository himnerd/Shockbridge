package com.himnerd.shockbridge.util;

import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCounted;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

public final class ByteBufUtils {

    private ByteBufUtils() {}

    public static int readVarInt(ByteBuf buf) {
        int value = 0;
        int shift = 0;
        byte b;
        do {
            if (!buf.isReadable()) throw new IndexOutOfBoundsException("VarInt underflow");
            b = buf.readByte();
            value |= (b & 0x7F) << shift;
            shift += 7;
            if (shift > 35) throw new ArithmeticException("VarInt overflow");
        } while ((b & 0x80) != 0);
        return value;
    }

    public static void writeVarInt(ByteBuf buf, int value) {
        while ((value & ~0x7F) != 0) {
            buf.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.writeByte(value & 0x7F);
    }

    public static int readSignedVarInt(ByteBuf buf) {
        int raw = readVarInt(buf);
        return (raw >>> 1) ^ -(raw & 1);
    }

    public static void writeSignedVarInt(ByteBuf buf, int value) {
        writeVarInt(buf, (value << 1) ^ (value >> 31));
    }

    public static long readVarLong(ByteBuf buf) {
        long value = 0;
        int shift = 0;
        byte b;
        do {
            if (!buf.isReadable()) throw new IndexOutOfBoundsException("VarLong underflow");
            b = buf.readByte();
            value |= (long) (b & 0x7F) << shift;
            shift += 7;
            if (shift > 70) throw new ArithmeticException("VarLong overflow");
        } while ((b & 0x80) != 0);
        return value;
    }

    public static void writeVarLong(ByteBuf buf, long value) {
        while ((value & ~0x7FL) != 0) {
            buf.writeByte((int) (value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.writeByte((int) value & 0x7F);
    }

    public static void writeSignedVarLong(ByteBuf buf, long value) {
        writeVarLong(buf, (value << 1) ^ (value >> 63));
    }

    public static String readString(ByteBuf buf) {
        int length = readVarInt(buf);
        if (length < 0 || length > buf.readableBytes()) {
            throw new IndexOutOfBoundsException("String length " + length + " exceeds readable " + buf.readableBytes());
        }
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static void writeString(ByteBuf buf, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(buf, bytes.length);
        buf.writeBytes(bytes);
    }

    public static InetSocketAddress readAddress(ByteBuf buf) {
        int version = buf.readUnsignedByte();
        try {
            if (version == 4) {
                byte[] addr = new byte[4];
                for (int i = 0; i < 4; i++) {
                    addr[i] = (byte) (~buf.readByte() & 0xFF);
                }
                int port = buf.readUnsignedShort();
                return new InetSocketAddress(InetAddress.getByAddress(addr), port);
            } else if (version == 6) {
                buf.skipBytes(2);
                int port = buf.readUnsignedShort();
                buf.skipBytes(4);
                byte[] addr = new byte[16];
                buf.readBytes(addr);
                buf.skipBytes(4);
                return new InetSocketAddress(InetAddress.getByAddress(addr), port);
            }
        } catch (UnknownHostException e) {
            return null;
        }
        return null;
    }

    public static void writeAddress(ByteBuf buf, InetSocketAddress address) {
        byte[] addr = address.getAddress().getAddress();
        if (addr.length == 4) {
            buf.writeByte(4);
            for (byte b : addr) {
                buf.writeByte(~b & 0xFF);
            }
            buf.writeShort(address.getPort());
        } else {
            buf.writeByte(6);
            buf.writeShort(23);
            buf.writeShort(address.getPort());
            buf.writeInt(0);
            buf.writeBytes(addr);
            buf.writeInt(0);
        }
    }

    public static void safeRelease(Object msg) {
        if (msg instanceof ReferenceCounted ref) {
            if (ref.refCnt() > 0) {
                ref.release();
            }
        }
    }
}