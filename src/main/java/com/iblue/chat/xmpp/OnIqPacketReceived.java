package com.iblue.chat.xmpp;

import com.iblue.chat.entities.Account;
import com.iblue.chat.xmpp.stanzas.IqPacket;

public interface OnIqPacketReceived extends PacketReceived {
	public void onIqPacketReceived(Account account, IqPacket packet);
}
