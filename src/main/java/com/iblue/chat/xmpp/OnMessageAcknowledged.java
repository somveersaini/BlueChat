package com.iblue.chat.xmpp;

import com.iblue.chat.entities.Account;

public interface OnMessageAcknowledged {
	public void onMessageAcknowledged(Account account, String id);
}
