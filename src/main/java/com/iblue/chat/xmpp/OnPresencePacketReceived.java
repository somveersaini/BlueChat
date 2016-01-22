package com.iblue.chat.xmpp;

import com.iblue.chat.entities.Account;
import com.iblue.chat.xmpp.stanzas.PresencePacket;

public interface OnPresencePacketReceived extends PacketReceived {
	public void onPresencePacketReceived(Account account, PresencePacket packet);
}
