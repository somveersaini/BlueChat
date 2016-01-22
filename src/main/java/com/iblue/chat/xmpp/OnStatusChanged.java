package com.iblue.chat.xmpp;

import com.iblue.chat.entities.Account;

public interface OnStatusChanged {
	public void onStatusChanged(Account account);
}
