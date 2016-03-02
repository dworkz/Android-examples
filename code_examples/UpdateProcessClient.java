package com.sample.mapbox.update;

import android.app.Activity;
import android.os.Bundle;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.FragmentManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.octo.android.robospice.persistence.exception.SpiceException;

import java.util.ArrayList;

/**
 * Update process client. Plays role of Controller if MVC pattern including:<br/><br/>
 *
 *  * MVC Model: Satbeams API data update model represented here by UpdateProcessManager;<br/>
 *  * MVC View: set of UI fragments like progress fragment, "preamble" (decision panel),  "monitor".<br/><br/>
 *
 * It implements simple state machine to interact with the Update Process Manager and show
 * appropriate UI fragment in the UI life cycle.<br/><br/>
 * Introduced primarily to implement the interaction both in Main Activity and
 * Updates Management Fragment (Settings).<br/><br/>
 *
 * Created by Crysberry on 14.07.2014.
 */
public class UpdateProcessClient

    implements

        UpdateProcessManager.Display,

        UpdatePreambleFragment.Listener,
        UpdateMonitorFragment.Listener

{
    public static final String TAG = UpdateProcessClient.class.getSimpleName();

    public interface EventListener {
        public void onUpdateProcessEvent(UpdateProcessEvent event);
    }

    /**
     * Background process state indicator and bring-to-foreground button.
     */
    public interface UpdateStateControl
    {
        public void bindUpdateStateControl(View.OnClickListener onActionButtonClickListener);

        public void showUpdateStateControl();
        public void hideUpdateStateControl();

        public void indicateUpdateState(UpdateProcessManager.STATE updateProcessState);
    }

    private void updateStateControlShow() {
        if (updateStateControl != null)
            updateStateControl.showUpdateStateControl();
    }

    private void updateStateControlHide() {
        if (updateStateControl != null)
            updateStateControl.hideUpdateStateControl();
    }

    private void updateStateControlIndicate(UpdateProcessManager.STATE updateProcessState) {
        if (updateStateControl != null)
            updateStateControl.indicateUpdateState(updateProcessState);
    }

    private static final String STATE_KEY_BUNDLE = TAG + ".STATE_KEY_BUNDLE";
    private static final String STATE_KEY_STATE = TAG + ".STATE_KEY_UPDATE_CLIENT_STATE";
    private static final String STATE_KEY_MODE = TAG + ".STATE_KEY_UPDATE_PROCESS_MODE";

    public enum STATE
    {
        /** Initial (initialized) state
         */
        ZERO,

        /** Unexpected, wrong state.
         */
        ILLEGAL,

        /**
         * Client in a state of waiting for info on updates available. The state indicated with an
         * indeterminate  progress indicator.
         */
        WAITING,

        /** Client tells user that there is nothing to do, i.e. no updates available.
         */
        NOTHING2DO,

        /** Client suggests user to make a decision:<br/><br/>
         *
         * - start update on foreground,<br/>
         * - start update in background,<br/>
         * - to update later,<br/><br/>
         *
         * if getting of update info finished successfully.<br/><br/>
         *
         * Or, asks to provide conditions necessary to load updates,
         * i.e. change system setting to get network access, or to
         * retry in case of server error.
         */
        DECIDING,

        /** User decided to postpone loading of updates.
         */
        POSTPONED,

        /** Client displays progress in foreground while updates loaded by the manager.<br/><br/>
         *
         *  It prevents user from any other interactions with the application through a modal
         *  dialog or popup.<br/><br/>
         *
         *  Allowed moving to background state.
         */
        FOREGROUND,

        /**
         * Client displays progress in such a way that allows user interactions with other
         * application functions. The background process indicated, for example, by an
         * indeterminate progress in the caption bar.<br/><br/>
         *
         * Allowed moving to foreground state.
         */
        BACKGROUND,

        /**
         * Client in the state to remind user that update process was interrupted
         * and cancelled by him.
         */
        INTERRUPTED,

        /** Just finished updates loading was successful.
         */
        SUCCESS,

        /** Last updates loading failed.
         */
        FAILURE,

        /** Client says that updates have been applied.
         */
        APPLIED
    }

    /**
     */
    private enum UPDATE_CONTROL_MODE {
        /** Ask user to decide whether to download update. */
        INTERACTIVE,
        /** Automatically download update. Applicable, e.g., when user requested full data reload. */
        AUTOMATIC
    }

    /**
     */
    Activity context;

    /**
     */
    UpdateProcessManager updateProcessManager;

    /**
     */
    FragmentManager uiFragmentManager;

    /**
     */
    STATE clientState = STATE.ZERO;

    /**
     */
    UPDATE_CONTROL_MODE updateControlMode = UPDATE_CONTROL_MODE.INTERACTIVE;

    /**
     */
    UpdateStateControl updateStateControl;

    /**
     */
    TextView statusTitleView;

    /**
     */
    TextView statusTextView;

    /**
     */
    public UpdateProcessClient(Activity context,
                               Bundle savedInstanceState,
                               FragmentManager fragmentManager,
                               UpdateStateControl updateStateControl)
    {
        this.context = context;

        if (savedInstanceState != null)
            onRestoreInstanceState(savedInstanceState);

        this.updateProcessManager = UpdateProcessManager.getInstance();
        this.uiFragmentManager = fragmentManager;
        this.updateStateControl = updateStateControl;

        if (updateStateControl != null)
            updateStateControl.bindUpdateStateControl(new ActionButtonClickListener());
    }

    public void bindUpdateStateControl(UpdateStateControl updateStateControl2) {
        if (updateStateControl != null)
            updateStateControl.hideUpdateStateControl();

        updateStateControl = updateStateControl2;
        updateStateControl.bindUpdateStateControl(new ActionButtonClickListener());
    }

    public void bindStatusMessageView(TextView titleView, TextView detailsView) {
        this.statusTitleView = titleView;
        this.statusTextView = detailsView;
    }

    public void unbindStatusMessageView() {
        this.statusTitleView = null;
        this.statusTextView = null;
    }


    public enum UpdateProcessEvent {
        POSTPONED,
        STARTED,
        FINISHED,
        FAILED,
        INTERRUPTED,
        APPLIED
    }

    ArrayList<EventListener> eventListeners = new ArrayList<EventListener>();

    public void subscribe(EventListener eventListener) {
        if (! eventListeners.contains(eventListener))
            eventListeners.add(eventListener);
    }

    public void unsubscribe(EventListener eventListener) {
        eventListeners.remove(eventListener);
    }

    private void notifyEventListeners(UpdateProcessEvent event) {
        for (EventListener listener : eventListeners) {
            listener.onUpdateProcessEvent(event);
        }
    }

    /** Connect the client to update manager
     */
    public void connect()
    {
        switch (clientState)
        {
            case ZERO: // 1st time connecting to the manager

                switch (updateProcessManager.getState())
                {
                    case INITIAL: // the manager is idle
                    case APPLIED:
                        break;
                    case FINISHED:
                        openMonitorDisplay();
                        break;

                    case INQUIRING:

                        clientState = STATE.WAITING;
                        showProgressIndicator(context.getString(R.string.update_progress_checking_for_updates));
                        updateProcessManager.attachDisplay(this);
                        break;

                    case READY2START: // there are updates - suggest to user
                    case CANCELLED:

                        openPreambleForm(); // STATE.DECIDING is set in the method
                        break;

                    case NO_UPDATES:

                        clientState = STATE.NOTHING2DO;
                        break;

                    case FAILURE:

                        // this failure case is possible when some prerequisites failed,
                        // for example storage availability
                        clientState = STATE.FAILURE;
                        openPreambleForm(); // STATE.DECIDING is set in the method
                        break;

                    /** Current paradigm is that only user can decide whether to load updates,
                     * postpone or cancel update process. What the only thing app does silently is
                     * checking for updates availability. This way, only cases realized above
                     * are legal here, when the client connects 1st time. (Note, client is to allow
                     * user make a decision).
                     */
                    case LOADING:
                        clientState = STATE.BACKGROUND;
                        updateStateControlIndicate(updateProcessManager.getState());
                        break;
                    default:
                        throw new IllegalStateException("Update process manager is in illegal state " + updateProcessManager.getState());
                }
                break;

            case WAITING: // client reconnected in another user interface while it was waiting before

                // show progress and attach to the update process
                showProgressIndicator(context.getString(R.string.update_progress_checking_for_updates));
                updateProcessManager.attachDisplay(this);
                break;

            case DECIDING:

                openPreambleForm(); // STATE.DECIDING is set in the method
                break;

            case FOREGROUND:

                openMonitorDisplay(); // STATE.FOREGROUND is set in the method
                break;

            case BACKGROUND:

                updateStateControlIndicate(updateProcessManager.getState());
                // STATE.BACKGROUND should have been set earlier when went to background
                break;

            /**
             * Other states can be delegated for display to the client users,
             * e.g., UpdatesManagementFragment if needed.
             */
        }
    }

    public STATE getState() {
        return clientState;
    }

    public boolean checkForUpdates()
    {
        if (updateProcessManager.isBusy())
            return false;

        showProgressIndicator(context.getString(R.string.update_client_progress_checking));
        updateProcessManager.attachDisplay(this);

        clientState = STATE.WAITING;

        updateProcessManager.restartUpdateDefinitionLoading();
        return true;
    }

    public boolean reloadAllData()
    {
        if (updateProcessManager.isBusy())
            return false;

        showProgressIndicator(context.getString(R.string.update_progress_preparing_full_reload));
        updateProcessManager.attachDisplay(this);

        clientState = STATE.WAITING;
        updateControlMode = UPDATE_CONTROL_MODE.AUTOMATIC;

        SatbeamsLocation.getInstance().suspend();

        updateProcessManager.restartForFullReload();
        return true;
    }


    public void onSaveInstanceState(Bundle outState) {
        Bundle clientStateBundle = new Bundle();
        clientStateBundle.putString(STATE_KEY_STATE, clientState.name());
        clientStateBundle.putString(STATE_KEY_MODE, updateControlMode.name());
        outState.putBundle(STATE_KEY_BUNDLE, clientStateBundle);
    }

    public void onRestoreInstanceState(Bundle savedInstanceState) {
        Bundle clientStateBundle = savedInstanceState.getBundle(STATE_KEY_BUNDLE);
        if (clientStateBundle == null)
            return;

        String clientStateString = (String) clientStateBundle.get(STATE_KEY_STATE);
        if (! TextUtils.isEmpty(clientStateString)) {
            clientState = STATE.valueOf(clientStateString);
        }

        String updateProcessModeString = clientStateBundle.getString(STATE_KEY_MODE);
        if (! TextUtils.isEmpty(updateProcessModeString)) {
            updateControlMode = UPDATE_CONTROL_MODE.valueOf(updateProcessModeString);
        }
    }

    public boolean onBackPressed()
    {
        switch (clientState)
        {
            case WAITING:

                updateProcessManager.detachDisplay(this);
                hideProgressIndicator();
                clientState = STATE.POSTPONED;
                return true;

            case DECIDING:
                updateProcessManager.detachDisplay(this);

                closePreambleForm();
                updateStateControlHide();
                clientState = STATE.POSTPONED;
                return true;

            case FOREGROUND:
                onUpdateGoesBackground(); // STATE.BACKGROUND is set in the method
                return true;
        }

        return false;
    }

    //
    // *** UpdateProcessManager.Display
    //

    @Override
    public void setUpdateErrorMessage(UpdateProcessManager client, String title, String details) {
        if (statusTitleView != null)
            statusTitleView.setText(title);
        if (statusTextView != null) {
            statusTextView.setText(details);
            statusTextView.setTextColor(context.getResources().getColor(R.color.message_attention));
        }
        if (statusTitleView == null && statusTextView == null) {
           Toast.makeText(context, title + (TextUtils.isEmpty(details)? "": " " + details),
                   Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void setButtons(UpdateProcessManager.ACTION... actions) {

    }

    @Override
    public void onSuccess(UpdateProcessManager reporter)
    {
        if (clientState == STATE.WAITING)
        {
            hideProgressIndicator();

            switch (updateProcessManager.getState())
            {
                case READY2START:

                    if (updateControlMode == UPDATE_CONTROL_MODE.AUTOMATIC)
                    {
                        SatbeamsLocation.getInstance().suspend();

                        UpdateProcessManager.getInstance().startUpdateLoading();
                        openMonitorDisplay(); // STATE.FOREGROUND is set in the method

                        updateControlMode = UPDATE_CONTROL_MODE.INTERACTIVE; // one time action mode
                    }
                    else {
                        openPreambleForm(); // STATE.DECIDING is set in the method
                    }
                    break;

                case NO_UPDATES:

                    clientState = STATE.NOTHING2DO;
                    notifyEventListeners(UpdateProcessEvent.FINISHED); // ???
                    break;

                default:
                    //clientState = STATE.ILLEGAL;
                    throw new IllegalStateException("Illegal update client state "+ clientState.name()  +" after checking for updates!");
            }

            return; // WAITING client state processed
        }

        switch(clientState)
        {
            case BACKGROUND:
                clientState = STATE.SUCCESS;
                updateStateControlIndicate(UpdateProcessManager.STATE.FINISHED);
                notifyEventListeners(UpdateProcessEvent.FINISHED);
                break;

            case FOREGROUND:
                clientState = STATE.SUCCESS;
                notifyEventListeners(UpdateProcessEvent.FINISHED); // not sure it's of use
                break;

            default:
                //clientState = STATE.ILLEGAL;
                throw new IllegalStateException("Illegal state after updates loading!");
        }
    }

    @Override
    public void onFailure(UpdateProcessManager reporter, SpiceException exception)
    {
        switch(clientState)
        {
            case WAITING:
                hideProgressIndicator();
                openPreambleForm(); // STATE.DECIDING is set in the method
                break;

            case BACKGROUND:
                clientState = STATE.FAILURE;
                break;

            case FOREGROUND:
                SatbeamsLocation.getInstance().resume(context);
                clientState = STATE.FAILURE;
                break;

            default:
                //clientState = STATE.ILLEGAL;
                throw new IllegalStateException("Illegal state after update manager failure!");
        }
    }

    //
    // *** UpdatePreambleFragment.Listener
    //

    @Override
    public void onPreambleSendsUpdate2Foreground()
    {
        clientState = STATE.FOREGROUND;
        notifyEventListeners(UpdateProcessEvent.STARTED);

        SatbeamsLocation.getInstance().suspend();

        UpdateProcessManager updateProcessManager = UpdateProcessManager.getInstance();
        Log.d(TAG, "Client requests foreground update from manager " + updateProcessManager.hashCode() + " in state " + updateProcessManager.getState().name());
        updateProcessManager.startUpdateLoading();

        closePreambleForm();
        updateStateControlHide();

        openMonitorDisplay();
    }

    @Override
    public void onPreambleSendsUpdate2Background()
    {
        clientState = STATE.BACKGROUND;
        notifyEventListeners(UpdateProcessEvent.STARTED);

        SatbeamsLocation.getInstance().resume(context);

        UpdateProcessManager.getInstance().startUpdateLoading();

        closePreambleForm();
        updateStateControlIndicate(UpdateProcessManager.STATE.LOADING);
    }


    @Override
    public void onPreamblePostponesUpdate()
    {
        clientState = STATE.POSTPONED;
        notifyEventListeners(UpdateProcessEvent.POSTPONED);

        SatbeamsLocation.getInstance().resume(context);

        closePreambleForm();
        updateStateControlHide();
    }

    @Override
    public void onPreambleRetry()
    {
        notifyEventListeners(UpdateProcessEvent.STARTED);
        closePreambleForm();
        if (updateControlMode == UPDATE_CONTROL_MODE.INTERACTIVE)
            checkForUpdates();
        else
            reloadAllData();
    }

    //
    // *** UpdateMonitorFragment.Listener
    //

    @Override
    public void onUpdateDataApplied()
    {
        closeMonitorDisplay();
        updateStateControlHide();

        SatbeamsLocation.getInstance().resume(context);

        SatelliteDataHandler.getInstance().restart(null);

        notifyEventListeners(UpdateProcessEvent.APPLIED);
        clientState = STATE.APPLIED;
    }

    @Override
    public void onUpdateGoesBackground()
    {
        closeMonitorDisplay();
        SatbeamsLocation.getInstance().resume(context);
        updateStateControlIndicate(UpdateProcessManager.STATE.LOADING);
        notifyEventListeners(UpdateProcessEvent.STARTED);
        clientState = STATE.BACKGROUND;
    }

    @Override
    public void onUpdateCancellation() {
        closeMonitorDisplay();
        SatbeamsLocation.getInstance().resume(context);
        updateStateControlHide();
        notifyEventListeners(UpdateProcessEvent.INTERRUPTED);
        clientState = STATE.INTERRUPTED;
    }

    //
    // ***
    //

    /**
     */
    private class ActionButtonClickListener implements View.OnClickListener
    {
        @Override
        public void onClick(View view) {
            openMonitorDisplay();
            updateStateControlHide();
        }
    }

    //
    // *** Show/Hide UI fragments
    //

    /** NOTE Fragment restored automatically on, e.g., screen rotation change.
     * At the same time, it may be restored again "manually" when state restored - so, this method should be reenterable.
     */
    private void openPreambleForm() {
        UpdatePreambleFragment upf = (UpdatePreambleFragment) uiFragmentManager
                .findFragmentByTag(UpdatePreambleFragment.TAG);
        if (upf == null) {
            upf = new UpdatePreambleFragment();
            upf.setStyle(DialogFragment.STYLE_NO_FRAME, android.R.style.Theme_Translucent);
            try {
                upf.show(uiFragmentManager, UpdatePreambleFragment.TAG);
            } catch (IllegalStateException e) {
                e.printStackTrace();
            }
        }
        upf.attach(this); // re-attach after, e.g., client recreated as main activity recreated on configuration change

        clientState = STATE.DECIDING;
    }

    private void closePreambleForm() {
        UpdatePreambleFragment upf = (UpdatePreambleFragment) uiFragmentManager
                .findFragmentByTag(UpdatePreambleFragment.TAG);
        if (upf != null) {
            upf.detach();
            upf.dismissAllowingStateLoss();
        }
    }

    private void openMonitorDisplay()
    {
        UpdateMonitorFragment umf = (UpdateMonitorFragment) uiFragmentManager
                .findFragmentByTag(UpdateMonitorFragment.TAG);
        if (umf == null) {
            umf = new UpdateMonitorFragment();
            umf.setStyle(DialogFragment.STYLE_NO_FRAME, android.R.style.Theme_Translucent);
            umf.show(uiFragmentManager, UpdateMonitorFragment.TAG);
        }
        umf.attach(this); // re-attach after, e.g., client recreated as main activity recreated on configuration change

        clientState = STATE.FOREGROUND;
    }

    private void closeMonitorDisplay() {
        UpdateMonitorFragment umf = (UpdateMonitorFragment) uiFragmentManager
                .findFragmentByTag(UpdateMonitorFragment.TAG);
        if (umf != null) {
            umf.dismissAllowingStateLoss();
            umf.detach();
        }
    }

    @Override
    public void setWakeMode(boolean mode) {

    }

    private void showProgressIndicator(String followerText) {
        hideProgressIndicator();
        FragmentManager fm = uiFragmentManager;
        ProgressFragment progressFragment = ProgressFragment.newInstance(followerText);
        progressFragment.setCancelable(false);
        progressFragment.setStyle(DialogFragment.STYLE_NO_FRAME, android.R.style.Theme_Translucent);
        progressFragment.show(fm, ProgressFragment.TAG);
    }

    private void hideProgressIndicator() {
        FragmentManager fm = uiFragmentManager;
        ProgressFragment progressFragment = (ProgressFragment) fm.findFragmentByTag(ProgressFragment.TAG);
        if (progressFragment != null)
            progressFragment.dismissAllowingStateLoss();
    }
}
