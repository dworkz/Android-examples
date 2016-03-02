package com.sample.mapbox.update;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.support.v4.app.DialogFragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.octo.android.robospice.persistence.exception.SpiceException;

/**
 *
 * TODO Add "onBackPressed" to get confirmation from user on cancellation or going to the background.
 *
 */
public class UpdateMonitorFragment extends DialogFragment
    implements
        UpdateProcessManager.Display,
        MultipleRequestsRunner.Display
{
    public static final String TAG = UpdateMonitorFragment.class.getSimpleName();

    private static final int REQUEST_CODE_SETTINGS = 123;

    /** This fragment listener
     *
     */
    public interface Listener
    {
        public void onUpdateDataApplied();
        public void onUpdateGoesBackground();
        public void onUpdateCancellation();
    }

    private class ProgressWidget
    {
        public ProgressBar bar;
        public ImageView status;
        public TextView follower; // percentage or quantity of the progress
        public TextView summary;
        public SmoothProgressBar indeterminate;

        public ProgressWidget(
                View root, int barResource, int statusResource,
                              int followerResource, int summaryResource, int ideterminateResource)
        {
            this.bar = (ProgressBar) root.findViewById(barResource);
            this.status = (ImageView) root.findViewById(statusResource);
            this.follower = (TextView) root.findViewById(followerResource);
            this.summary = (TextView) root.findViewById(summaryResource);

            this.indeterminate = (SmoothProgressBar) root.findViewById(ideterminateResource);
            indeterminate.setSmoothProgressDrawableBackgroundDrawable(
                    SmoothProgressBarUtils.generateDrawableWithColors(
                            getResources().getIntArray(R.array.pocket_background_colors),
                            ((SmoothProgressDrawable) indeterminate.getIndeterminateDrawable()).getStrokeWidth())
            );

            indeterminate.setVisibility(View.GONE);

            ViewUtils.setTaskStatusDrawable(status, ExecutionStatus.INITIAL);
        }

        public void startIndeterminate() {
            indeterminate.setVisibility(View.VISIBLE);
            indeterminate.progressiveStart();
        }

        public void stopIndeterminate() {
            //indeterminate.progressiveStop();
            indeterminate.setVisibility(View.GONE);
        }
    }


    private Listener listener;

    private boolean isEmbeddedMode = false;

    private ProgressWidget databaseProgress;
    private ProgressWidget filesProgress;

    private TextView errorsTitle, errorsDescription;

    private Button[] buttons = new Button[3];

    private OnButtonClickListener onButtonClickListener;

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

    public void setEmbeddedMode() {
        isEmbeddedMode = true;
    }

    /**
     */
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState)
    {
        View root = inflater.inflate(R.layout.fragment_update_monitor, container, false);

        if (UpdateProcessManager.getInstance().getState() == UpdateProcessManager.STATE.INITIAL) {
            Log.w(TAG, "Removing monitor due to full app reload!");
            getFragmentManager().popBackStackImmediate();
        }

        if (isEmbeddedMode) {
            root.findViewById(R.id.UpdateMonitorBlockHeader).setVisibility(View.GONE);
            root.findViewById(R.id.UpdateMonitorRootContainer).setBackgroundColor(Color.WHITE);
            root.findViewById(R.id.UpdateMonitorAlignmentContainer).setBackgroundColor(Color.WHITE);
        }

        // progress widgets

        databaseProgress = new ProgressWidget(root,
                R.id.UpdateMonitorProgressDatabase,
                R.id.UpdateMonitorStatusIndicatorDatabase,
                R.id.UpdateMonitorProgressFollowerDatabase,
                R.id.UpdateMonitorProgressSummaryDatabase,
                R.id.UpdateMonitor_PocketProgress_Database);

        filesProgress = new ProgressWidget(root,
                R.id.UpdateMonitorProgressFiles,
                R.id.UpdateMonitorStatusIndicatorFiles,
                R.id.UpdateMonitorProgressFollowerFiles,
                R.id.UpdateMonitorProgressSummaryFiles,
                R.id.UpdateMonitor_PocketProgress_Files);

        // errors description block

        errorsTitle = (TextView) root.findViewById(R.id.UpdateMonitorErrorsTitle);
        errorsDescription = (TextView) root.findViewById(R.id.UpdateMonitorErrorsDescription);

        // buttons

        buttons[0] = (Button) root.findViewById(R.id.UpdateMonitorButton01);
        buttons[1] = (Button) root.findViewById(R.id.UpdateMonitorButton02);
        buttons[2] = (Button) root.findViewById(R.id.UpdateMonitorButton03);

        onButtonClickListener = new OnButtonClickListener();
        for (Button b : buttons) {
            b.setOnClickListener(onButtonClickListener);
        }

        setButtons();

        return root;
    }

    private class OnButtonClickListener implements View.OnClickListener
    {
        @Override
        public void onClick(View v)
        {
            ACTION button = (ACTION) v.getTag();
            UpdateProcessManager manager = UpdateProcessManager.getInstance();

            switch(button)
            {
                case ApplyUpdate:
                    manager.detachDisplay(UpdateMonitorFragment.this);
                    manager.applyUpdatesLoaded();
                    if (listener != null) {
                        listener.onUpdateDataApplied();
                    }
                    break;

                case Go2Background:
                    manager.detachDisplay(UpdateMonitorFragment.this);
                    if (listener != null) {
                        manager.attachDisplay((UpdateProcessManager.Display) listener);
                        listener.onUpdateGoesBackground();
                    }
                    break;

                case RetryDownload:
                    hideErrors();
                    manager.retryUpdateLoading();
                    break;

                case ChangeSettings:
                    startActivityForResult(new Intent(android.provider.Settings.ACTION_SETTINGS), REQUEST_CODE_SETTINGS);

                default:
                case CancelUpdate:
                case CloseUpdate:
                    manager.detachDisplay(UpdateMonitorFragment.this);
                    manager.cancelUpdatesLoading();
                    if (listener != null) {
                        listener.onUpdateCancellation();
                    }
                    break;
            }
        }
    }

    @Override
    public void onStart() {
        super.onStart();

        UpdateProcessManager updateProcessManager = UpdateProcessManager.getInstance();

        // do not show fragment if the app was reloaded (UpdateProcessManager was reinitialized!)
        if (updateProcessManager.getState() == UpdateProcessManager.STATE.INITIAL) {
            Log.w(TAG, "Removing monitor due to full app reload!");
            dismissAllowingStateLoss();
            return;
        }

        Log.d(TAG, "Monitor attached to update manager " + updateProcessManager.hashCode() + " in state " + updateProcessManager.getState().name());
        updateProcessManager.attachDisplay(this);
    }

    @Override
    public void onStop() {
        super.onStop();
        UpdateProcessManager.getInstance().detachDisplay(this);
    }

    //
    // *** UPDATE MANAGER display implementation
    //

    @Override
    public void setWakeMode(boolean mode) {
        if (mode)
            getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else
            getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    public void setUpdateErrorMessage(UpdateProcessManager client, String title, String details) {
        showErrors(title, details);
    }

    /**
     */
    @Override
    public void setButtons(ACTION ... actions)
    {
        for (int i = 0; i < actions.length; i++) {
            buttons[i].setText(actions[i].t());
            buttons[i].setTag(actions[i]);
            buttons[i].setVisibility(View.VISIBLE);
        }

        for (int i = actions.length; i < buttons.length; i++) {
            buttons[i].setVisibility(View.GONE);
        }
    }

    @Override
    public void onSuccess(UpdateProcessManager reporter) {
        hideErrors();
    }

    @Override
    public void onFailure(UpdateProcessManager reporter, SpiceException exception) {
    }

    //
    // *** DATABASE & FILES UPDATE RUNNERS display interface implementation
    //

    @Override
    public void setProgressMax(MultipleRequestsRunner client, int value) {
        selectProgressWidget(client).bar.setMax(value);
    }

    @Override
    public void setCurrentProgress(MultipleRequestsRunner client, int value,
                                   String follower, ExecutionStatus status)
    {
        ProgressWidget progress = selectProgressWidget(client);
        progress.bar.setProgress(value);
        if (status != null)
            ViewUtils.setTaskStatusDrawable(progress.status, status);
        if (follower != null)
            progress.follower.setText(follower);
    }

    @Override
    public void setExecutionStatus(MultipleRequestsRunner client, ExecutionStatus status) {
        ViewUtils.setTaskStatusDrawable(selectProgressWidget(client).status, status);
    }

    @Override
    public void setProgressSummary(MultipleRequestsRunner client, String text) {
        selectProgressWidget(client).summary.setText(text);
    }

    @Override
    public void setIndeterminate(MultipleRequestsRunner client, boolean on) {
        ProgressWidget widget = selectProgressWidget(client);
        if (on)
            widget.startIndeterminate();
        else
            widget.stopIndeterminate();
    }

    @Override
    public void setErrorMessage(MultipleRequestsRunner client, String title, String details) {
        showErrors(title, details);
    }

    @Override
    public void onStart(MultipleRequestsRunner reporter) {
        selectProgressWidget(reporter).startIndeterminate();
    }

    @Override
    public void onSuccess(MultipleRequestsRunner reporter) {
        selectProgressWidget(reporter).stopIndeterminate();
    }

    @Override
    public void onFailure(MultipleRequestsRunner reporter, SpiceException exception) {
        selectProgressWidget(reporter).stopIndeterminate();
    }

    //
    // *** UTILITIES
    //

    private ProgressWidget selectProgressWidget(MultipleRequestsRunner client) {
        if (client.getRunnerId() == UpdateProcessManager.RUNNER.DatabaseUpdater)
            return databaseProgress;
        else
            return filesProgress;
    }

    private void showErrors(String title, String description) {
        errorsTitle.setText(title);
        errorsDescription.setText(description);
        errorsTitle.setVisibility(View.VISIBLE);
        errorsDescription.setVisibility(View.VISIBLE);
    }

    private void hideErrors() {
        errorsTitle.setVisibility(View.GONE);
        errorsDescription.setVisibility(View.GONE);
    }

    /**
     * example on how to modify progress (see the demo)
     * @param mProgressBar
     */
//    private void setValues(SmoothProgressBar mProgressBar) {
//
//        mProgressBar.setSmoothProgressDrawableSpeed(1.2f);
//        mProgressBar.setSmoothProgressDrawableSectionsCount(2);
//        mProgressBar.setSmoothProgressDrawableSeparatorLength(dpToPx(4));
//        mProgressBar.setSmoothProgressDrawableStrokeWidth(dpToPx(4));
//        mProgressBar.setSmoothProgressDrawableReversed(false);
//        mProgressBar.setSmoothProgressDrawableMirrorMode(false);
//        mProgressBar.setSmoothProgressDrawableUseGradients(false);
//
//        Interpolator interpolator;
//        int select = 3;
//        switch (select) {
//            case 1:
//                interpolator = new LinearInterpolator();
//                break;
//            case 2:
//                interpolator = new AccelerateDecelerateInterpolator();
//                break;
//            case 3:
//                interpolator = new DecelerateInterpolator();
//                break;
//            case 0:
//            default:
//                interpolator = new AccelerateInterpolator();
//                break;
//        }
//
//        mProgressBar.setSmoothProgressDrawableInterpolator(interpolator);
//        mProgressBar.setSmoothProgressDrawableColors(getResources().getIntArray(R.array.colors));
//    }
//
//    public int dpToPx(int dp) {
//        Resources r = getResources();
//        int px = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
//                dp, r.getDisplayMetrics());
//        return px;
//    }
}
