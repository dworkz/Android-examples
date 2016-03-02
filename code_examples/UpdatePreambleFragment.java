package com.sample.mapbox.update;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.support.v4.app.DialogFragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.octo.android.robospice.persistence.exception.SpiceException;

/**
 *
 */
public class UpdatePreambleFragment extends DialogFragment implements UpdateProcessManager.Display
{
    public static final String TAG = UpdatePreambleFragment.class.getSimpleName();

    public static final int REQUEST_CODE_SETTINGS = 101;

    private Listener listener;

    public interface Listener
    {
        public void onPreambleSendsUpdate2Foreground();
        public void onPreambleSendsUpdate2Background();
        public void onPreamblePostponesUpdate();
        public void onPreambleRetry();
    }

    TextView explanationView;
    TextView messageView;

    TextView[] menu = new TextView[3];

    public UpdatePreambleFragment() {
    }

    /**
     */
    public void attach(Listener listener) {
        this.listener = listener;
    }

    /**
     */
    public void detach() {
        listener = null;
    }

    /**
     */
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState)
    {
        View root = inflater.inflate(R.layout.fragment_update_preamble, container, false);

        UpdateProcessManager manager = UpdateProcessManager.getInstance(getActivity());

        setupViewReferences(root);

        if (manager.getState() == UpdateProcessManager.STATE.FAILURE) {
            setupForFailure(root);
            return root;
        }

        setupForSuccess(root);

        return root;
    }

    @Override
    public void onStart() {
        super.onStart();

        // do not show fragment if the app was reloaded (UpdateProcessManager was reinitialized!)
        UpdateProcessManager manager = UpdateProcessManager.getInstance(getActivity());
        if (manager.getState() == UpdateProcessManager.STATE.INITIAL) {
            Log.w(TAG, "Removing update preamble due to full app reload!");
            dismissAllowingStateLoss();
        }
    }

    private void setupViewReferences(View root)
    {
        messageView = ((TextView) root.findViewById(R.id.PreambleMessage));
        explanationView = (TextView) root.findViewById(R.id.PreambleExplanation);

        menu[0] = (TextView) root.findViewById(R.id.PreambleMenuOption01);
        menu[1] = (TextView) root.findViewById(R.id.PreambleMenuOption02);
        menu[2] = (TextView) root.findViewById(R.id.PreambleMenuOption03);
    }

    private void setupForSuccess(View root)
    {
        if (RestServiceSettings.getInstance(getActivity()).getLastUpdateMillis() > 0L) {
            ResponseGetUpdatedModel defResponse = UpdateProcessManager.getInstance(getActivity()).getUpdateDefinition();
            if (defResponse != null && defResponse.body != null) {
                ResponseBodyGetUpdatedModel def = defResponse.body;
                messageView.setText(R.string.threshold_screen_announce_regular_update);
                explanationView.setText(def.inShort());
            }
        }

        menu[0].setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) {
                    listener.onPreambleSendsUpdate2Foreground();
                }
            }
        });

        menu[1].setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) {
                    listener.onPreambleSendsUpdate2Background();
                }
            }
        });

        menu[2].setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) {
                    listener.onPreamblePostponesUpdate();
                }
            }
        });
    }

    private void setupForFailure(View root)
    {
        UpdateProcessManager manager = UpdateProcessManager.getInstance(getActivity());

        messageView.setText(manager.getLastErrorTitle());

        explanationView = (TextView) root.findViewById(R.id.PreambleExplanation);
        explanationView.setTextColor(Color.RED);
        explanationView.setText(manager.getLastErrorDetails());

        menu[0].setText("Retry now!");
        menu[0].setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null)
                    listener.onPreambleRetry();
            }
        });

        menu[1].setText("Check settings.");
        menu[1].setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivityForResult(new Intent(android.provider.Settings.ACTION_SETTINGS), REQUEST_CODE_SETTINGS);
                // TODO Check if that was network related error an open particular section.
            }
        });

        menu[2].setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null)
                    listener.onPreamblePostponesUpdate();
            }
        });
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

    }

    @Override
    public void setUpdateErrorMessage(UpdateProcessManager client, String title, String details) {
        setupForFailure(getView());
    }

    @Override
    public void setButtons(UpdateProcessManager.ACTION... actions) {
    }

    /**
     * Called back on successful retrial to recover from error.
     * @param reporter
     */
    @Override
    public void onSuccess(UpdateProcessManager reporter) {
        setupForSuccess(getView());
    }

    @Override
    public void onFailure(UpdateProcessManager reporter, SpiceException exception) {
        setupForFailure(getView());
    }

    @Override
    public void setWakeMode(boolean mode) {

    }
}
