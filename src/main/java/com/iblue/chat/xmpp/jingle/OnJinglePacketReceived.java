package com.iblue.chat.xmpp.jingle;

import com.iblue.chat.entities.Account;
import com.iblue.chat.xmpp.PacketReceived;
import com.iblue.chat.xmpp.jingle.stanzas.JinglePacket;

public interface OnJinglePacketReceived extends PacketReceived {
	void onJinglePacketReceived(Account account, JinglePacket packet);
}
