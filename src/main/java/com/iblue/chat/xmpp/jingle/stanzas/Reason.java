package com.iblue.chat.xmpp.jingle.stanzas;

import com.iblue.chat.xml.Element;

public class Reason extends Element {
	private Reason(String name) {
		super(name);
	}

	public Reason() {
		super("reason");
	}
}
