package com.iblue.chat.xmpp;

import com.iblue.chat.entities.Contact;

public interface OnContactStatusChanged {
	public void onContactStatusChanged(final Contact contact, final boolean online);
}
