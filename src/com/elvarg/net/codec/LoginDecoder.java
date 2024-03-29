package com.elvarg.net.codec;

import com.elvarg.Server;
import com.elvarg.net.ByteBufUtils;
import com.elvarg.net.NetworkConstants;
import com.elvarg.net.login.LoginDetailsMessage;
import com.elvarg.net.login.LoginResponses;
import com.elvarg.net.login.LoginUtils;
import com.elvarg.net.security.IsaacRandom;
import com.elvarg.util.Misc;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.List;
import java.util.Random;

/**
 * Decodes login.
 * @author Swiffy
 */
public final class LoginDecoder extends ByteToMessageDecoder {

	/**
	 * Generates random numbers via secure cryptography. Generates the session key for packet
	 * encryption.
	 */
	private static final Random random = new SecureRandom();

	/**
	 * The size of the encrypted data.
	 */
	private int encryptedLoginBlockSize;

	/**
	 * The current login decoder state
	 */
	private LoginDecoderState state = LoginDecoderState.LOGIN_REQUEST;
	
	@Override
	protected void decode(ChannelHandlerContext ctx, ByteBuf buffer, List<Object> out) throws Exception {
		switch(state) {
		
		case LOGIN_REQUEST:
			decodeRequest(ctx, buffer);
			break;
			
		case LOGIN_TYPE:
			decodeType(ctx, buffer);
			break;
			
		case LOGIN:
			decodeLogin(ctx, buffer, out);
			break;
		}
	}

	private void decodeRequest(ChannelHandlerContext ctx, ByteBuf buffer) {
		
		if(!buffer.isReadable()) {
			ctx.channel().close();
			return;
		}
		
		int request = buffer.readUnsignedByte();
		if (request != NetworkConstants.LOGIN_REQUEST_OPCODE) {
			Server.getLogger().info("Session rejected for bad login request id: "+request);
			LoginUtils.sendResponseCode(ctx, LoginResponses.LOGIN_BAD_SESSION_ID);
			return;
		}

		//Send information to the client
		ByteBuf buf = Unpooled.buffer(19);
		buf.writeByte(0); //0 = continue login
		buf.writeLong(random.nextLong()); // This long will be used for encryption later on
		ctx.writeAndFlush(buf);
		
		state = LoginDecoderState.LOGIN_TYPE;
	}

	private void decodeType(ChannelHandlerContext ctx, ByteBuf buffer) {
		
		if(!buffer.isReadable()) {
			ctx.channel().close();
			return;
		}
		
		int connectionType = buffer.readUnsignedByte();
		if (connectionType != NetworkConstants.NEW_CONNECTION_OPCODE
				&& connectionType != NetworkConstants.RECONNECTION_OPCODE) {
			Server.getLogger().info("Session rejected for bad connection type id: "+connectionType);
			LoginUtils.sendResponseCode(ctx, LoginResponses.LOGIN_BAD_SESSION_ID);
			return;
		}
		
		state = LoginDecoderState.LOGIN;
	}

	private void decodeLogin(ChannelHandlerContext ctx, ByteBuf buffer, List<Object> out) {
		
		if(!buffer.isReadable()) {
			ctx.channel().close();
			return;
		}
		
		encryptedLoginBlockSize = buffer.readUnsignedByte();


		if (encryptedLoginBlockSize != buffer.readableBytes()) {
			Server.getLogger().info(String.format("[host= %s] encryptedLoginBlockSize != readable bytes",
					ctx.channel().remoteAddress()));
			LoginUtils.sendResponseCode(ctx, LoginResponses.LOGIN_REJECT_SESSION);
			return;
		}

		if(buffer.isReadable(encryptedLoginBlockSize)) {

			int magicId = buffer.readUnsignedByte();
			if(magicId != 0xFF) {
				Server.getLogger().info(String.format("[host= %s] [magic= %d] was rejected for the wrong magic value.",
						ctx.channel().remoteAddress(), magicId));
				LoginUtils.sendResponseCode(ctx, LoginResponses.LOGIN_REJECT_SESSION);
				return;
			}

			int clientVersion = buffer.readShort();

			int memory =  buffer.readByte();
			if (memory != 0 && memory != 1) {
				Server.getLogger().info(String.format("[host= %s] was rejected for having the memory setting.",
						ctx.channel().remoteAddress()));
				LoginUtils.sendResponseCode(ctx, LoginResponses.LOGIN_REJECT_SESSION);
				return;
			}

			/*int[] archiveCrcs = new int[9];
			for (int i = 0; i < 9; i++) {
				archiveCrcs[i] = buffer.readInt();
			}*/

			/**
			 * Our RSA components. 
			 */
			int length = buffer.readUnsignedByte();
			byte[] rsaBytes = new byte[length];
			buffer.readBytes(rsaBytes);

			ByteBuf rsaBuffer = Unpooled.wrappedBuffer(new BigInteger(rsaBytes)
					.modPow(NetworkConstants.RSA_EXPONENT, NetworkConstants.RSA_MODULUS).toByteArray());

			int securityId = rsaBuffer.readByte();
			if(securityId != 10) {
				Server.getLogger().info(String.format("[host= %s] was rejected for having the wrong securityId.",
						ctx.channel().remoteAddress()));
				LoginUtils.sendResponseCode(ctx, LoginResponses.LOGIN_REJECT_SESSION);
				return;
			}

			long clientSeed = rsaBuffer.readLong();
			long seedReceived = rsaBuffer.readLong();

			int[] seed = {(int) (clientSeed >> 32), (int) clientSeed, (int) (seedReceived >> 32), (int) seedReceived};
			IsaacRandom decodingRandom = new IsaacRandom(seed);
			for (int i = 0; i < seed.length; i++) {
				seed[i] += 50;
			}

			int uid = rsaBuffer.readInt();

			String username = Misc.formatText(ByteBufUtils.readString(rsaBuffer).toLowerCase());
			String password = ByteBufUtils.readString(rsaBuffer);

			if (username.length() > 12 || password.length() > 20) {
				LoginUtils.sendResponseCode(ctx, LoginResponses.INVALID_CREDENTIALS_COMBINATION);
				return;
			}

			out.add(new LoginDetailsMessage(ctx, username, password, ByteBufUtils.getHost(ctx.channel()), clientVersion, uid, new IsaacRandom(seed), decodingRandom));
		}
	}
	
	private enum LoginDecoderState {
		LOGIN_REQUEST,
		LOGIN_TYPE,
		LOGIN;
	}
}
