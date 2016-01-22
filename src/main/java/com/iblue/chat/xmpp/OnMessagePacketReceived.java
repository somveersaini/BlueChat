package com.iblue.chat.xmpp;

import com.iblue.chat.entities.Account;
import com.iblue.chat.xmpp.stanzas.MessagePacket;

public interface OnMessagePacketReceived extends PacketReceived {
	public void onMessagePacketReceived(Account account, MessagePacket packet);
}
