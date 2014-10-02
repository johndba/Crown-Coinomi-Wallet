package com.coinomi.wallet.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import com.coinomi.core.coins.CoinType;
import com.coinomi.core.uri.CoinURI;
import com.coinomi.core.uri.CoinURIParseException;
import com.coinomi.core.wallet.SendRequest;
import com.coinomi.core.wallet.Wallet;
import com.coinomi.core.wallet.exceptions.NoSuchPocketException;
import com.coinomi.wallet.Constants;
import com.coinomi.wallet.R;
import com.coinomi.wallet.WalletApplication;
import com.coinomi.wallet.ui.widget.QrCodeButton;
import com.coinomi.wallet.util.Keyboard;
import com.google.bitcoin.core.Address;
import com.google.bitcoin.core.Coin;
import com.google.bitcoin.core.InsufficientMoneyException;
import com.google.bitcoin.crypto.KeyCrypterException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static com.coinomi.core.Preconditions.checkNotNull;

/**
 *  Fragment that prepares a transaction
 */
public class SendFragment extends Fragment {
    private static final Logger log = LoggerFactory.getLogger(SendFragment.class);

    // the fragment initialization parameters
    private static final String COIN_TYPE = "coin_type";
    private static final int REQUEST_CODE_SCAN = 0;
    private static final int SIGN_TRANSACTION = 1;

    private CoinType coinType;
    private WalletApplication application;
    private FragmentManager fragmentManager;
    private Handler handler = new Handler();
    private EditText receivingAddressView;
    private EditText sendAmountView;
    private QrCodeButton scanQrCodeButton;
    private Button sendConfirmButton;

    private State state = State.INPUT;
    private Address validatedAddress;
    private Coin sendAmount;
    private DialogFragment popupWindow;
    private Activity activity;
    @Nullable private WalletActivity mListener;


    private enum State {
        INPUT, PREPARATION, SENDING, SENT, FAILED
    }

    private final class SendAmountListener implements View.OnFocusChangeListener, TextWatcher {
        @Override
        public void onFocusChange(final View v, final boolean hasFocus) {
            if (!hasFocus) {
                validateAmount(true);
            }
        }

        @Override
        public void afterTextChanged(final Editable s) {
            validateAmount(false);
        }

        @Override
        public void beforeTextChanged(final CharSequence s, final int start, final int count, final int after) { }

        @Override
        public void onTextChanged(final CharSequence s, final int start, final int before, final int count) { }
    }
    private final SendAmountListener sendAmountListener = new SendAmountListener();

    /**
     * Use this factory method to create a new instance of
     * this fragment using the provided parameters.
     *
     * @param type the type of the coin
     * @return A new instance of fragment WalletSendCoins.
     */
    public static SendFragment newInstance(CoinType type) {
        SendFragment fragment = new SendFragment();
        Bundle args = new Bundle();
        args.putSerializable(COIN_TYPE, type);
        fragment.setArguments(args);
        return fragment;
    }
    public SendFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            coinType = (CoinType) getArguments().getSerializable(COIN_TYPE);
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View view = inflater.inflate(R.layout.fragment_send, container, false);

        receivingAddressView = (EditText) view.findViewById(R.id.send_to_address);
        sendAmountView = (EditText) view.findViewById(R.id.send_amount);
        sendAmountView.setOnFocusChangeListener(sendAmountListener);
        sendAmountView.addTextChangedListener(sendAmountListener);

        scanQrCodeButton = (QrCodeButton) view.findViewById(R.id.scan_qr_code);
        scanQrCodeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                handleScan();
            }
        });

        sendConfirmButton = (Button) view.findViewById(R.id.send_confirm);
        sendConfirmButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                validateReceivingAddress(true);
                if (everythingValid())
                    handleSendConfirm();
                else
                    requestFocusFirst();
            }
        });
        
        return view;
    }

    private void handleScan() {
        startActivityForResult(new Intent(getActivity(), ScanActivity.class), REQUEST_CODE_SCAN);
    }

    private void handleSendConfirm() {
        state = State.PREPARATION;
        updateView();
        if (mListener != null && mListener.getWalletApplication().getWallet() != null) {
            onMakeTransaction(mListener.getWalletApplication().getWallet(),
                    validatedAddress, sendAmount);
        }
        reset();
    }

    public void onMakeTransaction(Wallet wallet, Address toAddress, Coin amount) {
        Intent intent = new Intent(getActivity(), SignTransactionActivity.class);
        try {
            SendRequest request = checkNotNull(wallet).sendCoinsOffline(toAddress, amount);
            intent.putExtra(Constants.ARG_SEND_REQUEST, request);
            startActivityForResult(intent, SIGN_TRANSACTION);
        } catch (InsufficientMoneyException e) {
            Toast.makeText(getActivity(), R.string.send_coins_error_not_enough_money, Toast.LENGTH_LONG).show();
        } catch (NoSuchPocketException e) {
            Toast.makeText(getActivity(), R.string.no_such_pocket_error, Toast.LENGTH_LONG).show();
        }
    }

    private void reset() {
        state = State.INPUT;
        receivingAddressView.setText("");
        sendAmountView.setText("");
        validatedAddress = null;
        sendAmount = null;
        updateView();
    }

    @Override
    public void onActivityResult(final int requestCode, final int resultCode, final Intent intent) {
        if (requestCode == REQUEST_CODE_SCAN) {
            if (resultCode == Activity.RESULT_OK) {
                final String input = intent.getStringExtra(ScanActivity.INTENT_EXTRA_RESULT);

                try {
                    final CoinURI coinUri = new CoinURI(coinType, input);

                    Address address = coinUri.getAddress();
                    Coin amount = coinUri.getAmount();
                    String label = coinUri.getLabel();

                    updateStateFrom(address, amount, label);
                } catch (final CoinURIParseException x) {
                    String error = getResources().getString(R.string.uri_error, x.getMessage());
                    Toast.makeText(getActivity(), error, Toast.LENGTH_LONG).show();
                }
            }
        } else if (requestCode == SIGN_TRANSACTION) {
            if (resultCode == Activity.RESULT_OK) {
                Exception error = (Exception) intent.getSerializableExtra(Constants.ARG_ERROR);

                if (error == null) {
                    Toast.makeText(getActivity(), R.string.sent, Toast.LENGTH_SHORT).show();
                } else {
                    if (error instanceof InsufficientMoneyException) {
                        Toast.makeText(getActivity(), R.string.send_coins_error_not_enough_money, Toast.LENGTH_LONG).show();
                    } else if (error instanceof NoSuchPocketException) {
                        Toast.makeText(getActivity(), R.string.no_such_pocket_error, Toast.LENGTH_LONG).show();
                    } else if (error instanceof KeyCrypterException) {
                        Toast.makeText(getActivity(), R.string.password_failed, Toast.LENGTH_LONG).show();
                    } else if (error instanceof IOException) {
                        Toast.makeText(getActivity(), R.string.send_coins_error_network, Toast.LENGTH_LONG).show();
                    } else {
                        throw new RuntimeException(error);
                    }
                }
            }
        }
    }


    private void updateStateFrom(final Address address, final @Nullable Coin amount,
                                 final @Nullable String label) throws CoinURIParseException {
        log.info("got {}", address);
        if (address == null) {
            throw new CoinURIParseException("missing address");
        }

        // delay these actions until fragment is resumed
        handler.post(new Runnable()
        {
            @Override
            public void run() {
                validatedAddress = address;
                receivingAddressView.setText(address.toString());
                if (isAmountValid(amount)) {
                    sendAmountView.setText(amount.toPlainString());
                    sendAmount = amount;
                }
                requestFocusFirst();
                updateView();
            }
        });
    }

    private void updateView() {

//        viewCancel.setEnabled(state != State.PREPARATION);
        sendConfirmButton.setEnabled(everythingValid());

        // enable actions
        if (scanQrCodeButton != null) {
            scanQrCodeButton.setEnabled(state == State.INPUT);
        }
    }

    private boolean isOutputsValid() {
        return validatedAddress != null;
    }

    private boolean isAmountValid() {
        return isAmountValid(sendAmount);
    }

    private boolean isAmountValid(Coin amount) {
        // TODO, check if we have the available amount in the wallet
        return amount != null && amount.signum() > 0;
    }

    private boolean everythingValid() {
        return state == State.INPUT && isOutputsValid() && isAmountValid();
    }

    private void requestFocusFirst()
    {
        if (!isOutputsValid())
            receivingAddressView.requestFocus();
        else if (!isAmountValid())
            Keyboard.focusAndShowKeyboard(sendAmountView, getActivity());
        else if (everythingValid())
            sendConfirmButton.requestFocus();
        else
            log.warn("unclear focus");
    }

    private void validateAmount(final boolean popups) {
        try {
            Coin amount = Coin.parseCoin(String.valueOf(sendAmountView.getText()));
            if (isAmountValid(amount)) {
                sendAmount = amount;
            }
            else {
                sendAmount = null;
            }
        } catch (IllegalArgumentException ignore) {
            sendAmount = null;
        }
        updateView();
    }

    private void validateReceivingAddress(final boolean popups)
    {
        // TODO implement validation
//        try
//        {
//            final String addressStr = receivingAddressView.getText().toString().trim();
//            if (!addressStr.isEmpty())
//            {
//                final NetworkParameters addressParams = Address.getParametersFromAddress(addressStr);
//                if (addressParams != null && !addressParams.equals(Constants.NETWORK_PARAMETERS))
//                {
//                    // address is valid, but from different known network
//                    if (popups)
//                        popupMessage(receivingAddressView,
//                                getString(R.string.send_coins_fragment_receiving_address_error_cross_network, addressParams.getId()));
//                }
//                else if (addressParams == null)
//                {
//                    // address is valid, but from different unknown network
//                    if (popups)
//                        popupMessage(receivingAddressView, getString(R.string.send_coins_fragment_receiving_address_error_cross_network_unknown));
//                }
//                else
//                {
//                    // valid address
//                    final String label = AddressBookProvider.resolveLabel(activity, addressStr);
//                    validatedAddress = new AddressAndLabel(Constants.NETWORK_PARAMETERS, addressStr, label);
//                    receivingAddressView.setText(null);
//                }
//            }
//            else
//            {
//                // empty field should not raise error message
//            }
//        }
//        catch (final AddressFormatException x)
//        {
//            // could not decode address at all
//            if (popups)
//                popupMessage(receivingAddressView, getString(R.string.send_coins_fragment_receiving_address_error));
//        }
//
//        updateView();
    }

    //    // TODO: Rename method, update argument and hook method into UI event
//    public void onButtonPressed(Uri uri) {
//        if (mListener != null) {
//            mListener.onFragmentInteraction(uri);
//        }
//    }

    private void popupMessage(@Nonnull final View anchorNotUsed, @Nonnull final String message) {
        dismissPopup();

        Bundle bundle = new Bundle();
        bundle.putString(ErrorDialogFragment.MESSAGE, message);
        popupWindow = new ErrorDialogFragment();
        popupWindow.setArguments(bundle);
        popupWindow.show(fragmentManager, ErrorDialogFragment.TAG);
    }

    private void dismissPopup() {
        if (popupWindow != null) {
            popupWindow.dismiss();
            popupWindow = null;
        }
    }

    public static class ErrorDialogFragment extends DialogFragment {
        public static final String TAG = "error_dialog_fragment";
        public static final String MESSAGE = "message";
        private String message;

        public ErrorDialogFragment() {}

        @Override
        public void setArguments(Bundle args) {
            super.setArguments(args);
            message = args.getString(MESSAGE);
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            // Use the Builder class for convenient dialog construction
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
            builder.setMessage(message)
                    .setNeutralButton(R.string.button_dismiss, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
//                            dismissPopup();
                            dismiss();
                        }
                    });
            // Create the AlertDialog object and return it
            return builder.create();
        }
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);

        this.activity = activity;
        application = (WalletApplication) activity.getApplication();
        fragmentManager = getFragmentManager();
        try {
            mListener = (WalletActivity) activity;
        } catch (ClassCastException e) {
            throw new ClassCastException(activity.toString()
                    + " must implement " + WalletActivity.class);
        }
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mListener = null;
        application = null;
    }

}