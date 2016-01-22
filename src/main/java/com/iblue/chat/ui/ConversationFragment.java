package com.iblue.chat.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Fragment;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentSender.SendIntentException;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.text.InputType;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.AbsListView.OnScrollListener;
import android.widget.AdapterView;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.TextView.OnEditorActionListener;
import android.widget.Toast;

import net.java.otr4j.session.SessionStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.iblue.chat.Config;
import com.iblue.chat.R;
import com.iblue.chat.crypto.axolotl.AxolotlService;
import com.iblue.chat.entities.Account;
import com.iblue.chat.entities.Contact;
import com.iblue.chat.entities.Conversation;
import com.iblue.chat.entities.DownloadableFile;
import com.iblue.chat.entities.Message;
import com.iblue.chat.entities.MucOptions;
import com.iblue.chat.entities.Presences;
import com.iblue.chat.entities.Transferable;
import com.iblue.chat.entities.TransferablePlaceholder;
import com.iblue.chat.services.XmppConnectionService;
import com.iblue.chat.ui.XmppActivity.OnPresenceSelected;
import com.iblue.chat.ui.XmppActivity.OnValueEdited;
import com.iblue.chat.ui.adapter.MessageAdapter;
import com.iblue.chat.ui.adapter.MessageAdapter.OnContactPictureClicked;
import com.iblue.chat.ui.adapter.MessageAdapter.OnContactPictureLongClicked;
import com.iblue.chat.utils.GeoHelper;
import com.iblue.chat.utils.UIHelper;
import com.iblue.chat.xmpp.chatstate.ChatState;
import com.iblue.chat.xmpp.jid.Jid;

public class ConversationFragment extends Fragment implements EditMessage.KeyboardListener {

	protected Conversation conversation;
	private OnClickListener leaveMuc = new OnClickListener() {

		@Override
		public void onClick(View v) {
			activity.endConversation(conversation);
		}
	};
	private OnClickListener joinMuc = new OnClickListener() {

		@Override
		public void onClick(View v) {
			activity.xmppConnectionService.joinMuc(conversation);
		}
	};
	private OnClickListener enterPassword = new OnClickListener() {

		@Override
		public void onClick(View v) {
			MucOptions muc = conversation.getMucOptions();
			String password = muc.getPassword();
			if (password == null) {
				password = "";
			}
			activity.quickPasswordEdit(password, new OnValueEdited() {

				@Override
				public void onValueEdited(String value) {
					activity.xmppConnectionService.providePasswordForMuc(
							conversation, value);
				}
			});
		}
	};
	protected ListView messagesView;
	final protected List<Message> messageList = new ArrayList<>();
	protected MessageAdapter messageListAdapter;
	private EditMessage mEditMessage;
	private ImageButton mSendButton;
	private RelativeLayout snackbar;
	private TextView snackbarMessage;
	private TextView snackbarAction;
	private boolean messagesLoaded = true;
	private Toast messageLoaderToast;

	private OnScrollListener mOnScrollListener = new OnScrollListener() {

		@Override
		public void onScrollStateChanged(AbsListView view, int scrollState) {
			// TODO Auto-generated method stub

		}

		private int getIndexOf(String uuid, List<Message> messages) {
			if (uuid == null) {
				return 0;
			}
			for(int i = 0; i < messages.size(); ++i) {
				if (uuid.equals(messages.get(i).getUuid())) {
					return i;
				} else {
					Message next = messages.get(i);
					while(next != null && next.wasMergedIntoPrevious()) {
						if (uuid.equals(next.getUuid())) {
							return i;
						}
						next = next.next();
					}

				}
			}
			return 0;
		}

		@Override
		public void onScroll(AbsListView view, int firstVisibleItem,
							 int visibleItemCount, int totalItemCount) {
			synchronized (ConversationFragment.this.messageList) {
				if (firstVisibleItem < 5 && messagesLoaded && messageList.size() > 0) {
					long timestamp = ConversationFragment.this.messageList.get(0).getTimeSent();
					messagesLoaded = false;
					activity.xmppConnectionService.loadMoreMessages(conversation, timestamp, new XmppConnectionService.OnMoreMessagesLoaded() {
						@Override
						public void onMoreMessagesLoaded(final int c, Conversation conversation) {
							if (ConversationFragment.this.conversation != conversation) {
								return;
							}
							activity.runOnUiThread(new Runnable() {
								@Override
								public void run() {
									final int oldPosition = messagesView.getFirstVisiblePosition();
									Message message = messageList.get(oldPosition);
									String uuid = message != null ? message.getUuid() : null;
									View v = messagesView.getChildAt(0);
									final int pxOffset = (v == null) ? 0 : v.getTop();
									ConversationFragment.this.conversation.populateWithMessages(ConversationFragment.this.messageList);
									updateStatusMessages();
									messageListAdapter.notifyDataSetChanged();
									int pos = getIndexOf(uuid,messageList);
									messagesView.setSelectionFromTop(pos, pxOffset);
									messagesLoaded = true;
									if (messageLoaderToast != null) {
										messageLoaderToast.cancel();
									}
								}
							});
						}

						@Override
						public void informUser(final int resId) {

							activity.runOnUiThread(new Runnable() {
								@Override
								public void run() {
									if (messageLoaderToast != null) {
										messageLoaderToast.cancel();
									}
									if (ConversationFragment.this.conversation != conversation) {
										return;
									}
									messageLoaderToast = Toast.makeText(activity, resId, Toast.LENGTH_LONG);
									messageLoaderToast.show();
								}
							});

						}
					});

				}
			}
		}
	};
	private final int KEYCHAIN_UNLOCK_NOT_REQUIRED = 0;
	private final int KEYCHAIN_UNLOCK_REQUIRED = 1;
	private final int KEYCHAIN_UNLOCK_PENDING = 2;
	private int keychainUnlock = KEYCHAIN_UNLOCK_NOT_REQUIRED;
	protected OnClickListener clickToDecryptListener = new OnClickListener() {

		@Override
		public void onClick(View v) {
			if (keychainUnlock == KEYCHAIN_UNLOCK_REQUIRED
					&& activity.hasPgp() && !conversation.getAccount().getPgpDecryptionService().isRunning()) {
				keychainUnlock = KEYCHAIN_UNLOCK_PENDING;
				updateSnackBar(conversation);
				Message message = getLastPgpDecryptableMessage();
				if (message != null) {
					activity.xmppConnectionService.getPgpEngine().decrypt(message, new UiCallback<Message>() {
						@Override
						public void success(Message object) {
							conversation.getAccount().getPgpDecryptionService().onKeychainUnlocked();
							keychainUnlock = KEYCHAIN_UNLOCK_NOT_REQUIRED;
						}

						@Override
						public void error(int errorCode, Message object) {
							keychainUnlock = KEYCHAIN_UNLOCK_NOT_REQUIRED;
						}

						@Override
						public void userInputRequried(PendingIntent pi, Message object) {
							try {
								activity.startIntentSenderForResult(pi.getIntentSender(),
										ConversationActivity.REQUEST_DECRYPT_PGP, null, 0, 0, 0);
							} catch (SendIntentException e) {
								keychainUnlock = KEYCHAIN_UNLOCK_NOT_REQUIRED;
								updatePgpMessages();
							}
						}
					});
				}
			} else {
				keychainUnlock = KEYCHAIN_UNLOCK_NOT_REQUIRED;
				updatePgpMessages();
			}
		}
	};
	protected OnClickListener clickToVerify = new OnClickListener() {

		@Override
		public void onClick(View v) {
			activity.verifyOtrSessionDialog(conversation, v);
		}
	};
	private OnEditorActionListener mEditorActionListener = new OnEditorActionListener() {

		@Override
		public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
			if (actionId == EditorInfo.IME_ACTION_SEND) {
				InputMethodManager imm = (InputMethodManager) v.getContext()
						.getSystemService(Context.INPUT_METHOD_SERVICE);
				if (imm.isFullscreenMode()) {
					imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
				}
				sendMessage();
				return true;
			} else {
				return false;
			}
		}
	};
	private OnClickListener mSendButtonListener = new OnClickListener() {

		@Override
		public void onClick(View v) {
			Object tag = v.getTag();
			if (tag instanceof SendButtonAction) {
				SendButtonAction action = (SendButtonAction) tag;
				switch (action) {
					case TAKE_PHOTO:
						activity.attachFile(ConversationActivity.ATTACHMENT_CHOICE_TAKE_PHOTO);
						break;
					case SEND_LOCATION:
						activity.attachFile(ConversationActivity.ATTACHMENT_CHOICE_LOCATION);
						break;
					case RECORD_VOICE:
						activity.attachFile(ConversationActivity.ATTACHMENT_CHOICE_RECORD_VOICE);
						break;
					case CHOOSE_PICTURE:
						activity.attachFile(ConversationActivity.ATTACHMENT_CHOICE_CHOOSE_IMAGE);
						break;
					case CANCEL:
						if (conversation != null && conversation.getMode() == Conversation.MODE_MULTI) {
							conversation.setNextCounterpart(null);
							updateChatMsgHint();
							updateSendButton();
						}
						break;
					default:
						sendMessage();
				}
			} else {
				sendMessage();
			}
		}
	};
	private OnClickListener clickToMuc = new OnClickListener() {

		@Override
		public void onClick(View v) {
			Intent intent = new Intent(getActivity(), ConferenceDetailsActivity.class);
			intent.setAction(ConferenceDetailsActivity.ACTION_VIEW_MUC);
			intent.putExtra("uuid", conversation.getUuid());
			startActivity(intent);
		}
	};
	private ConversationActivity activity;
	private Message selectedMessage;

	private void sendMessage() {
		final String body = mEditMessage.getText().toString();
		if (body.length() == 0 || this.conversation == null) {
			return;
		}
		Message message = new Message(conversation, body, conversation.getNextEncryption());
		if (conversation.getMode() == Conversation.MODE_MULTI) {
			if (conversation.getNextCounterpart() != null) {
				message.setCounterpart(conversation.getNextCounterpart());
				message.setType(Message.TYPE_PRIVATE);
			}
		}
		switch (conversation.getNextEncryption()) {
			case Message.ENCRYPTION_OTR:
				sendOtrMessage(message);
				break;
			case Message.ENCRYPTION_PGP:
				sendPgpMessage(message);
				break;
			case Message.ENCRYPTION_AXOLOTL:
				if(!activity.trustKeysIfNeeded(ConversationActivity.REQUEST_TRUST_KEYS_TEXT)) {
					sendAxolotlMessage(message);
				}
				break;
			default:
				sendPlainTextMessage(message);
		}
	}

	public void updateChatMsgHint() {
		final boolean multi = conversation.getMode() == Conversation.MODE_MULTI;
		if (multi && conversation.getNextCounterpart() != null) {
			this.mEditMessage.setHint(getString(
					R.string.send_private_message_to,
					conversation.getNextCounterpart().getResourcepart()));
		} else if (multi && !conversation.getMucOptions().participating()) {
			this.mEditMessage.setHint(R.string.you_are_not_participating);
		} else {
			switch (conversation.getNextEncryption()) {
				case Message.ENCRYPTION_NONE:
					mEditMessage
							.setHint(getString(R.string.send_unencrypted_message));
					break;
				case Message.ENCRYPTION_OTR:
					mEditMessage.setHint(getString(R.string.send_otr_message));
					break;
				case Message.ENCRYPTION_AXOLOTL:
					AxolotlService axolotlService = conversation.getAccount().getAxolotlService();
					if (axolotlService != null && axolotlService.trustedSessionVerified(conversation)) {
						mEditMessage.setHint(getString(R.string.send_omemo_x509_message));
					} else {
						mEditMessage.setHint(getString(R.string.send_omemo_message));
					}
					break;
				case Message.ENCRYPTION_PGP:
					mEditMessage.setHint(getString(R.string.send_pgp_message));
					break;
				default:
					break;
			}
			getActivity().invalidateOptionsMenu();
		}
	}

	public void setupIme() {
		if (activity == null) {
			return;
		} else if (activity.usingEnterKey() && activity.enterIsSend()) {
			mEditMessage.setInputType(mEditMessage.getInputType() & (~InputType.TYPE_TEXT_FLAG_MULTI_LINE));
			mEditMessage.setInputType(mEditMessage.getInputType() & (~InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE));
		} else if (activity.usingEnterKey()) {
			mEditMessage.setInputType(mEditMessage.getInputType() | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
			mEditMessage.setInputType(mEditMessage.getInputType() & (~InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE));
		} else {
			mEditMessage.setInputType(mEditMessage.getInputType() | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
			mEditMessage.setInputType(mEditMessage.getInputType() | InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE);
		}
	}

	@Override
	public View onCreateView(final LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		final View view = inflater.inflate(R.layout.fragment_conversation, container, false);
		view.setOnClickListener(null);
		mEditMessage = (EditMessage) view.findViewById(R.id.textinput);
		mEditMessage.setOnClickListener(new OnClickListener() {

			@Override
			public void onClick(View v) {
				if (activity != null) {
					activity.hideConversationsOverview();
				}
			}
		});
		mEditMessage.setOnEditorActionListener(mEditorActionListener);

		mSendButton = (ImageButton) view.findViewById(R.id.textSendButton);
		mSendButton.setOnClickListener(this.mSendButtonListener);

		snackbar = (RelativeLayout) view.findViewById(R.id.snackbar);
		snackbarMessage = (TextView) view.findViewById(R.id.snackbar_message);
		snackbarAction = (TextView) view.findViewById(R.id.snackbar_action);

		messagesView = (ListView) view.findViewById(R.id.messages_view);
		messagesView.setOnScrollListener(mOnScrollListener);
		messagesView.setTranscriptMode(ListView.TRANSCRIPT_MODE_NORMAL);
		messageListAdapter = new MessageAdapter((ConversationActivity) getActivity(), this.messageList);
		messageListAdapter.setOnContactPictureClicked(new OnContactPictureClicked() {

			@Override
			public void onContactPictureClicked(Message message) {
				if (message.getStatus() <= Message.STATUS_RECEIVED) {
					if (message.getConversation().getMode() == Conversation.MODE_MULTI) {
						if (message.getCounterpart() != null) {
							String user = message.getCounterpart().isBareJid() ? message.getCounterpart().toString() : message.getCounterpart().getResourcepart();
							if (!message.getConversation().getMucOptions().isUserInRoom(user)) {
								Toast.makeText(activity,activity.getString(R.string.user_has_left_conference,user),Toast.LENGTH_SHORT).show();
							}
							highlightInConference(user);
						}
					} else {
						activity.switchToContactDetails(message.getContact(), message.getAxolotlFingerprint());
					}
				} else {
					Account account = message.getConversation().getAccount();
					Intent intent = new Intent(activity, EditAccountActivity.class);
					intent.putExtra("jid", account.getJid().toBareJid().toString());
					intent.putExtra("fingerprint", message.getAxolotlFingerprint());
					startActivity(intent);
				}
			}
		});
		messageListAdapter
				.setOnContactPictureLongClicked(new OnContactPictureLongClicked() {

					@Override
					public void onContactPictureLongClicked(Message message) {
						if (message.getStatus() <= Message.STATUS_RECEIVED) {
							if (message.getConversation().getMode() == Conversation.MODE_MULTI) {
								if (message.getCounterpart() != null) {
									String user = message.getCounterpart().getResourcepart();
									if (user != null) {
										if (message.getConversation().getMucOptions().isUserInRoom(user)) {
											privateMessageWith(message.getCounterpart());
										} else {
											Toast.makeText(activity, activity.getString(R.string.user_has_left_conference, user), Toast.LENGTH_SHORT).show();
										}
									}
								}
							}
						} else {
							activity.showQrCode();
						}
					}
				});
		messagesView.setAdapter(messageListAdapter);

		registerForContextMenu(messagesView);

		return view;
	}

	@Override
	public void onCreateContextMenu(ContextMenu menu, View v,
									ContextMenuInfo menuInfo) {
		synchronized (this.messageList) {
			super.onCreateContextMenu(menu, v, menuInfo);
			AdapterView.AdapterContextMenuInfo acmi = (AdapterContextMenuInfo) menuInfo;
			this.selectedMessage = this.messageList.get(acmi.position);
			populateContextMenu(menu);
		}
	}

	private void populateContextMenu(ContextMenu menu) {
		final Message m = this.selectedMessage;
		if (m.getType() != Message.TYPE_STATUS) {
			activity.getMenuInflater().inflate(R.menu.message_context, menu);
			menu.setHeaderTitle(R.string.message_options);
			MenuItem copyText = menu.findItem(R.id.copy_text);
			MenuItem shareWith = menu.findItem(R.id.share_with);
			MenuItem sendAgain = menu.findItem(R.id.send_again);
			MenuItem copyUrl = menu.findItem(R.id.copy_url);
			MenuItem downloadFile = menu.findItem(R.id.download_file);
			MenuItem cancelTransmission = menu.findItem(R.id.cancel_transmission);
			if ((m.getType() == Message.TYPE_TEXT || m.getType() == Message.TYPE_PRIVATE)
					&& m.getTransferable() == null
					&& !GeoHelper.isGeoUri(m.getBody())
					&& m.treatAsDownloadable() != Message.Decision.MUST) {
				copyText.setVisible(true);
			}
			if ((m.getType() != Message.TYPE_TEXT
					&& m.getType() != Message.TYPE_PRIVATE
					&& m.getTransferable() == null)
					|| (GeoHelper.isGeoUri(m.getBody()))) {
				shareWith.setVisible(true);
			}
			if (m.getStatus() == Message.STATUS_SEND_FAILED) {
				sendAgain.setVisible(true);
			}
			if (m.hasFileOnRemoteHost()
					|| GeoHelper.isGeoUri(m.getBody())
					|| m.treatAsDownloadable() == Message.Decision.MUST) {
				copyUrl.setVisible(true);
			}
			if ((m.getType() == Message.TYPE_TEXT && m.getTransferable() == null && m.treatAsDownloadable() != Message.Decision.NEVER)
					|| (m.isFileOrImage() && m.getTransferable() instanceof TransferablePlaceholder && m.hasFileOnRemoteHost())){
				downloadFile.setVisible(true);
				downloadFile.setTitle(activity.getString(R.string.download_x_file,UIHelper.getFileDescriptionString(activity, m)));
			}
			if ((m.getTransferable() != null && !(m.getTransferable() instanceof TransferablePlaceholder))
					|| (m.isFileOrImage() && (m.getStatus() == Message.STATUS_WAITING
					|| m.getStatus() == Message.STATUS_OFFERED))) {
				cancelTransmission.setVisible(true);
			}
		}
	}

	@Override
	public boolean onContextItemSelected(MenuItem item) {
		switch (item.getItemId()) {
			case R.id.share_with:
				shareWith(selectedMessage);
				return true;
			case R.id.copy_text:
				copyText(selectedMessage);
				return true;
			case R.id.send_again:
				resendMessage(selectedMessage);
				return true;
			case R.id.copy_url:
				copyUrl(selectedMessage);
				return true;
			case R.id.download_file:
				downloadFile(selectedMessage);
				return true;
			case R.id.cancel_transmission:
				cancelTransmission(selectedMessage);
				return true;
			default:
				return super.onContextItemSelected(item);
		}
	}

	private void shareWith(Message message) {
		Intent shareIntent = new Intent();
		shareIntent.setAction(Intent.ACTION_SEND);
		if (GeoHelper.isGeoUri(message.getBody())) {
			shareIntent.putExtra(Intent.EXTRA_TEXT, message.getBody());
			shareIntent.setType("text/plain");
		} else {
			shareIntent.putExtra(Intent.EXTRA_STREAM,
					activity.xmppConnectionService.getFileBackend()
							.getJingleFileUri(message));
			shareIntent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
			String mime = message.getMimeType();
			if (mime == null) {
				mime = "*/*";
			}
			shareIntent.setType(mime);
		}
		try {
			activity.startActivity(Intent.createChooser(shareIntent, getText(R.string.share_with)));
		} catch (ActivityNotFoundException e) {
			//This should happen only on faulty androids because normally chooser is always available
			Toast.makeText(activity,R.string.no_application_found_to_open_file,Toast.LENGTH_SHORT).show();
		}
	}

	private void copyText(Message message) {
		if (activity.copyTextToClipboard(message.getMergedBody(),
				R.string.message_text)) {
			Toast.makeText(activity, R.string.message_copied_to_clipboard,
					Toast.LENGTH_SHORT).show();
		}
	}

	private void resendMessage(Message message) {
		if (message.getType() == Message.TYPE_FILE || message.getType() == Message.TYPE_IMAGE) {
			DownloadableFile file = activity.xmppConnectionService.getFileBackend().getFile(message);
			if (!file.exists()) {
				Toast.makeText(activity, R.string.file_deleted, Toast.LENGTH_SHORT).show();
				message.setTransferable(new TransferablePlaceholder(Transferable.STATUS_DELETED));
				return;
			}
		}
		activity.xmppConnectionService.resendFailedMessages(message);
	}

	private void copyUrl(Message message) {
		final String url;
		final int resId;
		if (GeoHelper.isGeoUri(message.getBody())) {
			resId = R.string.location;
			url = message.getBody();
		} else if (message.hasFileOnRemoteHost()) {
			resId = R.string.file_url;
			url = message.getFileParams().url.toString();
		} else {
			url = message.getBody().trim();
			resId = R.string.file_url;
		}
		if (activity.copyTextToClipboard(url, resId)) {
			Toast.makeText(activity, R.string.url_copied_to_clipboard,
					Toast.LENGTH_SHORT).show();
		}
	}

	private void downloadFile(Message message) {
		activity.xmppConnectionService.getHttpConnectionManager()
				.createNewDownloadConnection(message,true);
	}

	private void cancelTransmission(Message message) {
		Transferable transferable = message.getTransferable();
		if (transferable != null) {
			transferable.cancel();
		} else {
			activity.xmppConnectionService.markMessage(message, Message.STATUS_SEND_FAILED);
		}
	}

	protected void privateMessageWith(final Jid counterpart) {
		this.mEditMessage.setText("");
		this.conversation.setNextCounterpart(counterpart);
		updateChatMsgHint();
		updateSendButton();
	}

	protected void highlightInConference(String nick) {
		String oldString = mEditMessage.getText().toString().trim();
		if (oldString.isEmpty() || mEditMessage.getSelectionStart() == 0) {
			mEditMessage.getText().insert(0, nick + ": ");
		} else {
			if (mEditMessage.getText().charAt(
					mEditMessage.getSelectionStart() - 1) != ' ') {
				nick = " " + nick;
			}
			mEditMessage.getText().insert(mEditMessage.getSelectionStart(),
					nick + " ");
		}
	}

	@Override
	public void onStop() {
		super.onStop();
		if (this.conversation != null) {
			final String msg = mEditMessage.getText().toString();
			this.conversation.setNextMessage(msg);
			updateChatState(this.conversation, msg);
		}
	}

	private void updateChatState(final Conversation conversation, final String msg) {
		ChatState state = msg.length() == 0 ? Config.DEFAULT_CHATSTATE : ChatState.PAUSED;
		Account.State status = conversation.getAccount().getStatus();
		if (status == Account.State.ONLINE && conversation.setOutgoingChatState(state)) {
			activity.xmppConnectionService.sendChatState(conversation);
		}
	}

	public void reInit(Conversation conversation) {
		if (conversation == null) {
			return;
		}
		this.activity = (ConversationActivity) getActivity();
		setupIme();
		if (this.conversation != null) {
			final String msg = mEditMessage.getText().toString();
			this.conversation.setNextMessage(msg);
			if (this.conversation != conversation) {
				updateChatState(this.conversation, msg);
			}
			this.conversation.trim();
		}

		this.keychainUnlock = KEYCHAIN_UNLOCK_NOT_REQUIRED;
		this.conversation = conversation;
		if (this.conversation.getMode() == Conversation.MODE_MULTI) {
			this.conversation.setNextCounterpart(null);
		}
		boolean canWrite = this.conversation.getMode() == Conversation.MODE_SINGLE || this.conversation.getMucOptions().participating();
		this.mEditMessage.setEnabled(canWrite);
		this.mSendButton.setEnabled(canWrite);
		this.mEditMessage.setKeyboardListener(null);
		this.mEditMessage.setText("");
		this.mEditMessage.append(this.conversation.getNextMessage());
		this.mEditMessage.setKeyboardListener(this);
		messageListAdapter.updatePreferences();
		this.messagesView.setAdapter(messageListAdapter);
		updateMessages();
		this.messagesLoaded = true;
		int size = this.messageList.size();
		if (size > 0) {
			messagesView.setSelection(size - 1);
		}
	}

	private OnClickListener mUnblockClickListener = new OnClickListener() {
		@Override
		public void onClick(final View v) {
			v.post(new Runnable() {
				@Override
				public void run() {
					v.setVisibility(View.INVISIBLE);
				}
			});
			if (conversation.isDomainBlocked()) {
				BlockContactDialog.show(activity, activity.xmppConnectionService, conversation);
			} else {
				activity.unblockConversation(conversation);
			}
		}
	};

	private OnClickListener mAddBackClickListener = new OnClickListener() {

		@Override
		public void onClick(View v) {
			final Contact contact = conversation == null ? null : conversation.getContact();
			if (contact != null) {
				activity.xmppConnectionService.createContact(contact);
				activity.switchToContactDetails(contact);
			}
		}
	};

	private OnClickListener mUnmuteClickListener = new OnClickListener() {

		@Override
		public void onClick(final View v) {
			activity.unmuteConversation(conversation);
		}
	};

	private OnClickListener mAnswerSmpClickListener = new OnClickListener() {
		@Override
		public void onClick(View view) {
			Intent intent = new Intent(activity, VerifyOTRActivity.class);
			intent.setAction(VerifyOTRActivity.ACTION_VERIFY_CONTACT);
			intent.putExtra("contact", conversation.getContact().getJid().toBareJid().toString());
			intent.putExtra("account", conversation.getAccount().getJid().toBareJid().toString());
			intent.putExtra("mode", VerifyOTRActivity.MODE_ANSWER_QUESTION);
			startActivity(intent);
		}
	};

	private void updateSnackBar(final Conversation conversation) {
		final Account account = conversation.getAccount();
		final Contact contact = conversation.getContact();
		final int mode = conversation.getMode();
		if (conversation.isBlocked()) {
			showSnackbar(R.string.contact_blocked, R.string.unblock, this.mUnblockClickListener);
		} else if (!contact.showInRoster() && contact.getOption(Contact.Options.PENDING_SUBSCRIPTION_REQUEST)) {
			showSnackbar(R.string.contact_added_you, R.string.add_back, this.mAddBackClickListener);
		} else if (mode == Conversation.MODE_MULTI
				&& !conversation.getMucOptions().online()
				&& account.getStatus() == Account.State.ONLINE) {
			switch (conversation.getMucOptions().getError()) {
				case MucOptions.ERROR_NICK_IN_USE:
					showSnackbar(R.string.nick_in_use, R.string.edit, clickToMuc);
					break;
				case MucOptions.ERROR_UNKNOWN:
					showSnackbar(R.string.conference_not_found, R.string.leave, leaveMuc);
					break;
				case MucOptions.ERROR_PASSWORD_REQUIRED:
					showSnackbar(R.string.conference_requires_password, R.string.enter_password, enterPassword);
					break;
				case MucOptions.ERROR_BANNED:
					showSnackbar(R.string.conference_banned, R.string.leave, leaveMuc);
					break;
				case MucOptions.ERROR_MEMBERS_ONLY:
					showSnackbar(R.string.conference_members_only, R.string.leave, leaveMuc);
					break;
				case MucOptions.KICKED_FROM_ROOM:
					showSnackbar(R.string.conference_kicked, R.string.join, joinMuc);
					break;
				default:
					break;
			}
		} else if (keychainUnlock == KEYCHAIN_UNLOCK_REQUIRED) {
			showSnackbar(R.string.openpgp_messages_found, R.string.decrypt, clickToDecryptListener);
		} else if (mode == Conversation.MODE_SINGLE
				&& conversation.smpRequested()) {
			showSnackbar(R.string.smp_requested, R.string.verify, this.mAnswerSmpClickListener);
		} else if (mode == Conversation.MODE_SINGLE
				&& conversation.hasValidOtrSession()
				&& (conversation.getOtrSession().getSessionStatus() == SessionStatus.ENCRYPTED)
				&& (!conversation.isOtrFingerprintVerified())) {
			showSnackbar(R.string.unknown_otr_fingerprint, R.string.verify, clickToVerify);
		} else if (conversation.isMuted()) {
			showSnackbar(R.string.notifications_disabled, R.string.enable, this.mUnmuteClickListener);
		} else {
			hideSnackbar();
		}
	}

	public void updateMessages() {
		synchronized (this.messageList) {
			if (getView() == null) {
				return;
			}
			final ConversationActivity activity = (ConversationActivity) getActivity();
			if (this.conversation != null) {
				conversation.populateWithMessages(ConversationFragment.this.messageList);
				updatePgpMessages();
				updateSnackBar(conversation);
				updateStatusMessages();
				this.messageListAdapter.notifyDataSetChanged();
				updateChatMsgHint();
				if (!activity.isConversationsOverviewVisable() || !activity.isConversationsOverviewHideable()) {
					activity.sendReadMarkerIfNecessary(conversation);
				}
				this.updateSendButton();
			}
		}
	}

	public void updatePgpMessages() {
		if (keychainUnlock != KEYCHAIN_UNLOCK_PENDING) {
			if (getLastPgpDecryptableMessage() != null
					&& !conversation.getAccount().getPgpDecryptionService().isRunning()) {
				keychainUnlock = KEYCHAIN_UNLOCK_REQUIRED;
			} else {
				keychainUnlock = KEYCHAIN_UNLOCK_NOT_REQUIRED;
			}
		}
	}

	@Nullable
	private Message getLastPgpDecryptableMessage() {
		for (final Message message : this.messageList) {
			if (message.getEncryption() == Message.ENCRYPTION_PGP
					&& (message.getStatus() == Message.STATUS_RECEIVED || message.getStatus() >= Message.STATUS_SEND)
					&& message.getTransferable() == null) {
				return message;
			}
		}
		return null;
	}

	private void messageSent() {
		int size = this.messageList.size();
		messagesView.setSelection(size - 1);
		mEditMessage.setText("");
		updateChatMsgHint();
	}

	public void setFocusOnInputField() {
		mEditMessage.requestFocus();
	}

	enum SendButtonAction {TEXT, TAKE_PHOTO, SEND_LOCATION, RECORD_VOICE, CANCEL, CHOOSE_PICTURE}

	private int getSendButtonImageResource(SendButtonAction action, int status) {
		switch (action) {
			case TEXT:
				switch (status) {
					case Presences.CHAT:
					case Presences.ONLINE:
						return R.drawable.ic_send_text_online;
					case Presences.AWAY:
						return R.drawable.ic_send_text_away;
					case Presences.XA:
					case Presences.DND:
						return R.drawable.ic_send_text_dnd;
					default:
						return R.drawable.ic_send_text_offline;
				}
			case TAKE_PHOTO:
				switch (status) {
					case Presences.CHAT:
					case Presences.ONLINE:
						return R.drawable.ic_send_photo_online;
					case Presences.AWAY:
						return R.drawable.ic_send_photo_away;
					case Presences.XA:
					case Presences.DND:
						return R.drawable.ic_send_photo_dnd;
					default:
						return R.drawable.ic_send_photo_offline;
				}
			case RECORD_VOICE:
				switch (status) {
					case Presences.CHAT:
					case Presences.ONLINE:
						return R.drawable.ic_send_voice_online;
					case Presences.AWAY:
						return R.drawable.ic_send_voice_away;
					case Presences.XA:
					case Presences.DND:
						return R.drawable.ic_send_voice_dnd;
					default:
						return R.drawable.ic_send_voice_offline;
				}
			case SEND_LOCATION:
				switch (status) {
					case Presences.CHAT:
					case Presences.ONLINE:
						return R.drawable.ic_send_location_online;
					case Presences.AWAY:
						return R.drawable.ic_send_location_away;
					case Presences.XA:
					case Presences.DND:
						return R.drawable.ic_send_location_dnd;
					default:
						return R.drawable.ic_send_location_offline;
				}
			case CANCEL:
				switch (status) {
					case Presences.CHAT:
					case Presences.ONLINE:
						return R.drawable.ic_send_cancel_online;
					case Presences.AWAY:
						return R.drawable.ic_send_cancel_away;
					case Presences.XA:
					case Presences.DND:
						return R.drawable.ic_send_cancel_dnd;
					default:
						return R.drawable.ic_send_cancel_offline;
				}
			case CHOOSE_PICTURE:
				switch (status) {
					case Presences.CHAT:
					case Presences.ONLINE:
						return R.drawable.ic_send_picture_online;
					case Presences.AWAY:
						return R.drawable.ic_send_picture_away;
					case Presences.XA:
					case Presences.DND:
						return R.drawable.ic_send_picture_dnd;
					default:
						return R.drawable.ic_send_picture_offline;
				}
		}
		return R.drawable.ic_send_text_offline;
	}

	public void updateSendButton() {
		final Conversation c = this.conversation;
		final SendButtonAction action;
		final int status;
		final boolean empty = this.mEditMessage == null || this.mEditMessage.getText().length() == 0;
		final boolean conference = c.getMode() == Conversation.MODE_MULTI;
		if (conference && !c.getAccount().httpUploadAvailable()) {
			if (empty && c.getNextCounterpart() != null) {
				action = SendButtonAction.CANCEL;
			} else {
				action = SendButtonAction.TEXT;
			}
		} else {
			if (empty) {
				if (conference && c.getNextCounterpart() != null) {
					action = SendButtonAction.CANCEL;
				} else {
					String setting = activity.getPreferences().getString("quick_action", "recent");
					if (!setting.equals("none") && UIHelper.receivedLocationQuestion(conversation.getLatestMessage())) {
						setting = "location";
					} else if (setting.equals("recent")) {
						setting = activity.getPreferences().getString("recently_used_quick_action", "text");
					}
					switch (setting) {
						case "photo":
							action = SendButtonAction.TAKE_PHOTO;
							break;
						case "location":
							action = SendButtonAction.SEND_LOCATION;
							break;
						case "voice":
							action = SendButtonAction.RECORD_VOICE;
							break;
						case "picture":
							action = SendButtonAction.CHOOSE_PICTURE;
							break;
						default:
							action = SendButtonAction.TEXT;
							break;
					}
				}
			} else {
				action = SendButtonAction.TEXT;
			}
		}
		if (activity.useSendButtonToIndicateStatus() && c != null
				&& c.getAccount().getStatus() == Account.State.ONLINE) {
			if (c.getMode() == Conversation.MODE_SINGLE) {
				status = c.getContact().getMostAvailableStatus();
			} else {
				status = c.getMucOptions().online() ? Presences.ONLINE : Presences.OFFLINE;
			}
		} else {
			status = Presences.OFFLINE;
		}
		this.mSendButton.setTag(action);
		this.mSendButton.setImageResource(getSendButtonImageResource(action, status));
	}

	protected void updateStatusMessages() {
		synchronized (this.messageList) {
			if (conversation.getMode() == Conversation.MODE_SINGLE) {
				ChatState state = conversation.getIncomingChatState();
				if (state == ChatState.COMPOSING) {
					this.messageList.add(Message.createStatusMessage(conversation, getString(R.string.contact_is_typing, conversation.getName())));
				} else if (state == ChatState.PAUSED) {
					this.messageList.add(Message.createStatusMessage(conversation, getString(R.string.contact_has_stopped_typing, conversation.getName())));
				} else {
					for (int i = this.messageList.size() - 1; i >= 0; --i) {
						if (this.messageList.get(i).getStatus() == Message.STATUS_RECEIVED) {
							return;
						} else {
							if (this.messageList.get(i).getStatus() == Message.STATUS_SEND_DISPLAYED) {
								this.messageList.add(i + 1,
										Message.createStatusMessage(conversation, getString(R.string.contact_has_read_up_to_this_point, conversation.getName())));
								return;
							}
						}
					}
				}
			}
		}
	}

	protected void showSnackbar(final int message, final int action,
								final OnClickListener clickListener) {
		snackbar.setVisibility(View.VISIBLE);
		snackbar.setOnClickListener(null);
		snackbarMessage.setText(message);
		snackbarMessage.setOnClickListener(null);
		snackbarAction.setVisibility(View.VISIBLE);
		snackbarAction.setText(action);
		snackbarAction.setOnClickListener(clickListener);
	}

	protected void hideSnackbar() {
		snackbar.setVisibility(View.GONE);
	}

	protected void sendPlainTextMessage(Message message) {
		ConversationActivity activity = (ConversationActivity) getActivity();
		activity.xmppConnectionService.sendMessage(message);
		messageSent();
	}

	protected void sendPgpMessage(final Message message) {
		final ConversationActivity activity = (ConversationActivity) getActivity();
		final XmppConnectionService xmppService = activity.xmppConnectionService;
		final Contact contact = message.getConversation().getContact();
		if (!activity.hasPgp()) {
			activity.showInstallPgpDialog();
			return;
		}
		if (conversation.getAccount().getPgpSignature() == null) {
			activity.announcePgp(conversation.getAccount(), conversation);
			return;
		}
		if (conversation.getMode() == Conversation.MODE_SINGLE) {
			if (contact.getPgpKeyId() != 0) {
				xmppService.getPgpEngine().hasKey(contact,
						new UiCallback<Contact>() {

							@Override
							public void userInputRequried(PendingIntent pi,
														  Contact contact) {
								activity.runIntent(
										pi,
										ConversationActivity.REQUEST_ENCRYPT_MESSAGE);
							}

							@Override
							public void success(Contact contact) {
								messageSent();
								activity.encryptTextMessage(message);
							}

							@Override
							public void error(int error, Contact contact) {
								System.out.println();
							}
						});

			} else {
				showNoPGPKeyDialog(false,
						new DialogInterface.OnClickListener() {

							@Override
							public void onClick(DialogInterface dialog,
												int which) {
								conversation
										.setNextEncryption(Message.ENCRYPTION_NONE);
								xmppService.databaseBackend
										.updateConversation(conversation);
								message.setEncryption(Message.ENCRYPTION_NONE);
								xmppService.sendMessage(message);
								messageSent();
							}
						});
			}
		} else {
			if (conversation.getMucOptions().pgpKeysInUse()) {
				if (!conversation.getMucOptions().everybodyHasKeys()) {
					Toast warning = Toast
							.makeText(getActivity(),
									R.string.missing_public_keys,
									Toast.LENGTH_LONG);
					warning.setGravity(Gravity.CENTER_VERTICAL, 0, 0);
					warning.show();
				}
				activity.encryptTextMessage(message);
				messageSent();
			} else {
				showNoPGPKeyDialog(true,
						new DialogInterface.OnClickListener() {

							@Override
							public void onClick(DialogInterface dialog,
												int which) {
								conversation
										.setNextEncryption(Message.ENCRYPTION_NONE);
								message.setEncryption(Message.ENCRYPTION_NONE);
								xmppService.databaseBackend
										.updateConversation(conversation);
								xmppService.sendMessage(message);
								messageSent();
							}
						});
			}
		}
	}

	public void showNoPGPKeyDialog(boolean plural,
								   DialogInterface.OnClickListener listener) {
		AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
		builder.setIconAttribute(android.R.attr.alertDialogIcon);
		if (plural) {
			builder.setTitle(getString(R.string.no_pgp_keys));
			builder.setMessage(getText(R.string.contacts_have_no_pgp_keys));
		} else {
			builder.setTitle(getString(R.string.no_pgp_key));
			builder.setMessage(getText(R.string.contact_has_no_pgp_key));
		}
		builder.setNegativeButton(getString(R.string.cancel), null);
		builder.setPositiveButton(getString(R.string.send_unencrypted),
				listener);
		builder.create().show();
	}

	protected void sendAxolotlMessage(final Message message) {
		final ConversationActivity activity = (ConversationActivity) getActivity();
		final XmppConnectionService xmppService = activity.xmppConnectionService;
		xmppService.sendMessage(message);
		messageSent();
	}

	protected void sendOtrMessage(final Message message) {
		final ConversationActivity activity = (ConversationActivity) getActivity();
		final XmppConnectionService xmppService = activity.xmppConnectionService;
		activity.selectPresence(message.getConversation(),
				new OnPresenceSelected() {

					@Override
					public void onPresenceSelected() {
						message.setCounterpart(conversation.getNextCounterpart());
						xmppService.sendMessage(message);
						messageSent();
					}
				});
	}

	public void appendText(String text) {
		if (text == null) {
			return;
		}
		String previous = this.mEditMessage.getText().toString();
		if (previous.length() != 0 && !previous.endsWith(" ")) {
			text = " " + text;
		}
		this.mEditMessage.append(text);
	}

	@Override
	public boolean onEnterPressed() {
		if (activity.enterIsSend()) {
			sendMessage();
			return true;
		} else {
			return false;
		}
	}

	@Override
	public void onTypingStarted() {
		Account.State status = conversation.getAccount().getStatus();
		if (status == Account.State.ONLINE && conversation.setOutgoingChatState(ChatState.COMPOSING)) {
			activity.xmppConnectionService.sendChatState(conversation);
		}
		activity.hideConversationsOverview();
		updateSendButton();
	}

	@Override
	public void onTypingStopped() {
		Account.State status = conversation.getAccount().getStatus();
		if (status == Account.State.ONLINE && conversation.setOutgoingChatState(ChatState.PAUSED)) {
			activity.xmppConnectionService.sendChatState(conversation);
		}
	}

	@Override
	public void onTextDeleted() {
		Account.State status = conversation.getAccount().getStatus();
		if (status == Account.State.ONLINE && conversation.setOutgoingChatState(Config.DEFAULT_CHATSTATE)) {
			activity.xmppConnectionService.sendChatState(conversation);
		}
		updateSendButton();
	}

	private int completionIndex = 0;
	private int lastCompletionLength = 0;
	private String incomplete;
	private int lastCompletionCursor;
	private boolean firstWord = false;

	@Override
	public boolean onTabPressed(boolean repeated) {
		if (conversation == null || conversation.getMode() == Conversation.MODE_SINGLE) {
			return false;
		}
		if (repeated) {
			completionIndex++;
		} else {
			lastCompletionLength = 0;
			completionIndex = 0;
			final String content = mEditMessage.getText().toString();
			lastCompletionCursor = mEditMessage.getSelectionEnd();
			int start = lastCompletionCursor > 0 ? content.lastIndexOf(" ",lastCompletionCursor-1) + 1 : 0;
			firstWord = start == 0;
			incomplete = content.substring(start,lastCompletionCursor);
		}
		List<String> completions = new ArrayList<>();
		for(MucOptions.User user : conversation.getMucOptions().getUsers()) {
			if (user.getName().startsWith(incomplete)) {
				completions.add(user.getName()+(firstWord ? ": " : " "));
			}
		}
		Collections.sort(completions);
		if (completions.size() > completionIndex) {
			String completion = completions.get(completionIndex).substring(incomplete.length());
			mEditMessage.getEditableText().delete(lastCompletionCursor,lastCompletionCursor + lastCompletionLength);
			mEditMessage.getEditableText().insert(lastCompletionCursor, completion);
			lastCompletionLength = completion.length();
		} else {
			completionIndex = -1;
			mEditMessage.getEditableText().delete(lastCompletionCursor,lastCompletionCursor + lastCompletionLength);
			lastCompletionLength = 0;
		}
		return true;
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode,
	                                final Intent data) {
		if (resultCode == Activity.RESULT_OK) {
			if (requestCode == ConversationActivity.REQUEST_DECRYPT_PGP) {
				activity.getSelectedConversation().getAccount().getPgpDecryptionService().onKeychainUnlocked();
				keychainUnlock = KEYCHAIN_UNLOCK_NOT_REQUIRED;
				updatePgpMessages();
			} else if (requestCode == ConversationActivity.REQUEST_TRUST_KEYS_TEXT) {
				final String body = mEditMessage.getText().toString();
				Message message = new Message(conversation, body, conversation.getNextEncryption());
				sendAxolotlMessage(message);
			} else if (requestCode == ConversationActivity.REQUEST_TRUST_KEYS_MENU) {
				int choice = data.getIntExtra("choice", ConversationActivity.ATTACHMENT_CHOICE_INVALID);
				activity.selectPresenceToAttachFile(choice, conversation.getNextEncryption());
			}
		} else {
			if (requestCode == ConversationActivity.REQUEST_DECRYPT_PGP) {
				keychainUnlock = KEYCHAIN_UNLOCK_NOT_REQUIRED;
				updatePgpMessages();
			}
		}
	}

}
