/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.modbus.studer.internal;

import static org.openhab.binding.modbus.studer.internal.StuderBindingConstants.*;

import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.core.library.types.QuantityType;
import org.eclipse.smarthome.core.library.types.StringType;
import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.ThingStatusInfo;
import org.eclipse.smarthome.core.thing.ThingTypeUID;
import org.eclipse.smarthome.core.thing.binding.BaseThingHandler;
import org.eclipse.smarthome.core.thing.binding.ThingHandler;
import org.eclipse.smarthome.core.types.Command;
import org.openhab.binding.modbus.handler.ModbusEndpointThingHandler;
import org.openhab.binding.modbus.studer.internal.StuderParser.ModeXtender;
import org.openhab.binding.modbus.studer.internal.StuderParser.State;
import org.openhab.binding.modbus.studer.internal.StuderParser.VSMode;
import org.openhab.binding.modbus.studer.internal.StuderParser.VTMode;
import org.openhab.binding.modbus.studer.internal.StuderParser.VTType;
import org.openhab.io.transport.modbus.AsyncModbusFailure;
import org.openhab.io.transport.modbus.ModbusCommunicationInterface;
import org.openhab.io.transport.modbus.ModbusReadFunctionCode;
import org.openhab.io.transport.modbus.ModbusReadRequestBlueprint;
import org.openhab.io.transport.modbus.ModbusRegisterArray;
import org.openhab.io.transport.modbus.PollTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link StuderHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Giovanni Mirulla - Initial contribution
 */
@SuppressWarnings("unused")
@NonNullByDefault
public class StuderHandler extends BaseThingHandler {

    private final Logger logger = LoggerFactory.getLogger(StuderHandler.class);

    private @Nullable StuderConfiguration config;

    /**
     * Array of tasks used to poll the device
     */
    private ArrayList<PollTask> pollTasks = new ArrayList<PollTask>();

    /**
     * Communication interface to the slave endpoint we're connecting to
     */
    protected volatile @Nullable ModbusCommunicationInterface comms = null;

    /**
     * Importing parser methods and enums
     */
    final StuderParser parser = new StuderParser();
    /**
     * Support variable for type of thing
     */
    protected ThingTypeUID type;

    /**
     * Array of registers of Studer slave to read, we store this once initialization is complete
     */
    private Integer[] registers = new Integer[0];

    /**
     * Instances of this handler
     *
     * @param thing the thing to handle
     * @param type the type of thing to handle
     * @param slaveAddress the address of thing
     * @param refreshSec the address of thing
     */
    public StuderHandler(Thing thing) {
        super(thing);
        this.type = thing.getThingTypeUID();
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {

        // Currently we do not support any commands
    }

    /**
     * Initialization:
     * Load the config object
     * Connect to the slave bridge
     * Get registers to poll
     * Start the periodic polling
     */
    @SuppressWarnings("null")
    @Override
    public void initialize() {
        config = getConfigAs(StuderConfiguration.class);
        logger.debug("Initializing thing whit configuration: {}", thing.getConfiguration());

        startUp();
    }

    /*
     * This method starts the operation of this handler
     * Connect to the slave bridge
     * Get registers to poll
     * Start the periodic polling
     */
    @SuppressWarnings("null")
    private void startUp() {

        connectEndpoint();

        if (comms == null || config == null) {
            logger.debug("Invalid endpoint/config/manager ref for studer handler");
            return;
        }

        if (!pollTasks.isEmpty()) {
            return;
        }

        if (type.equals(THING_TYPE_BSP)) {
            Set<Integer> keys = CHANNELS_BSP.keySet();
            registers = keys.toArray(new Integer[keys.size()]);
        } else if (type.equals(THING_TYPE_XTENDER)) {
            Set<Integer> keys = CHANNELS_XTENDER.keySet();
            registers = keys.toArray(new Integer[keys.size()]);
        } else if (type.equals(THING_TYPE_VARIOTRACK)) {
            Set<Integer> keys = CHANNELS_VARIOTRACK.keySet();
            registers = keys.toArray(new Integer[keys.size()]);
        } else if (type.equals(THING_TYPE_VARIOSTRING)) {
            Set<Integer> keys = CHANNELS_VARIOSTRING.keySet();
            registers = keys.toArray(new Integer[keys.size()]);
        }

        for (int r : registers) {
            registerPollTask(r);

        }
    }

    /**
     * Dispose the binding correctly
     */
    @Override
    public void dispose() {
        tearDown();
    }

    /**
     * Unregister the poll tasks and release the endpoint reference
     */
    private void tearDown() {
        unregisterPollTasks();
        unregisterEndpoint();
    }

    /**
     * Get the endpoint handler from the bridge this handler is connected to
     * Checks that we're connected to the right type of bridge
     *
     * @return the endpoint handler or null if the bridge does not exist
     */
    private @Nullable ModbusEndpointThingHandler getEndpointThingHandler() {
        Bridge bridge = getBridge();
        if (bridge == null) {
            logger.debug("Bridge is null");
            return null;
        }
        if (bridge.getStatus() != ThingStatus.ONLINE) {
            logger.debug("Bridge is not online");
            return null;
        }

        ThingHandler handler = bridge.getHandler();
        if (handler == null) {
            logger.debug("Bridge handler is null");
            return null;
        }

        if (handler instanceof ModbusEndpointThingHandler) {
            ModbusEndpointThingHandler slaveEndpoint = (ModbusEndpointThingHandler) handler;
            return slaveEndpoint;
        } else {
            logger.debug("Unexpected bridge handler: {}", handler);
            return null;
        }
    }

    /**
     * Get a reference to the modbus endpoint
     */
    private void connectEndpoint() {
        if (comms != null) {
            return;
        }

        ModbusEndpointThingHandler slaveEndpointThingHandler = getEndpointThingHandler();
        if (slaveEndpointThingHandler == null) {
            @SuppressWarnings("null")
            String label = Optional.ofNullable(getBridge()).map(b -> b.getLabel()).orElse("<null>");
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE,
                    String.format("Bridge '%s' is offline", label));
            logger.debug("No bridge handler available -- aborting init for {}", label);
            return;
        }
        comms = slaveEndpointThingHandler.getCommunicationInterface();
        if (comms == null) {
            @SuppressWarnings("null")
            String label = Optional.ofNullable(getBridge()).map(b -> b.getLabel()).orElse("<null>");
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE,
                    String.format("Bridge '%s' not completely initialized", label));
            logger.debug("Bridge not initialized fully (no endpoint) -- aborting init for {}", this);
            return;
        }
    }

    /**
     * Remove the endpoint if exists
     */
    private void unregisterEndpoint() {
        // Comms will be close()'d by endpoint thing handler
        comms = null;
    }

    private synchronized void unregisterPollTasks() {
        if (pollTasks.isEmpty()) {
            return;
        }
        logger.debug("Unregistering polling from ModbusManager");
        @Nullable
        ModbusCommunicationInterface mycomms = comms;
        if (mycomms != null) {
            for (PollTask t : pollTasks) {
                mycomms.unregisterRegularPoll(t);
            }
            pollTasks.clear();
        }
    }

    /**
     * Register poll task
     * This is where we set up our regular poller
     */
    @SuppressWarnings("null")
    private synchronized void registerPollTask(int registerNumber) {
        if (pollTasks.size() >= registers.length) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR);
            throw new IllegalStateException("New pollTask invalid");
        }
        @Nullable
        ModbusCommunicationInterface mycomms = comms;
        @Nullable
        StuderConfiguration studerConfig = config;
        if (studerConfig == null || mycomms == null) {
            throw new IllegalStateException("registerPollTask called without proper configuration");
        }

        logger.debug("Setting up regular polling");

        ModbusReadRequestBlueprint request = new ModbusReadRequestBlueprint(studerConfig.slaveAddress,
                ModbusReadFunctionCode.READ_INPUT_REGISTERS, registerNumber, 2, studerConfig.maxTries);
        long refreshMillis = studerConfig.refreshSec * 1000;
        PollTask pollTask = mycomms.registerRegularPoll(request, refreshMillis, 1000, result -> {
            if (result.getRegisters().isPresent()) {
                ModbusRegisterArray reg = result.getRegisters().get();
                handlePolledData(registerNumber, reg);
            } else {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR);
                return;
            }
            if (getThing().getStatus() != ThingStatus.ONLINE) {
                updateStatus(ThingStatus.ONLINE);
            }
        }, this::handleError);
        pollTasks.add(pollTask);
    }

    /**
     * This method is called each time new data has been polled from the modbus slave
     * The register array is first parsed, then each of the channels are updated
     * to the new values
     *
     * @param n register readed
     * @param registers byte array read from the modbus slave
     */
    protected void handlePolledData(int registerNumber, ModbusRegisterArray registers) {
        String hexString = registers.toHexString().toString();
        Object quantity = parser.hexToFloat(hexString);
        if (quantity != null) {
            if (type.equals(THING_TYPE_BSP)) {
                updateState(CHANNELS_BSP.get(registerNumber),
                        new QuantityType<>((float) quantity, UNIT_CHANNELS_BSP.get(registerNumber)));
            } else if (type.equals(THING_TYPE_XTENDER)) {
                handlePolledDataXtender(registerNumber, quantity);
            } else if (type.equals(THING_TYPE_VARIOTRACK)) {
                handlePolledDataVarioTrack(registerNumber, quantity);
            } else if (type.equals(THING_TYPE_VARIOSTRING)) {
                handlePolledDataVarioString(registerNumber, quantity);
            }
        }
        resetCommunicationError();
    }

    /**
     * This method is called each time new data has been polled from the VarioString slave
     * The register array is first parsed, then each of the channels are updated
     * to the new values
     */
    protected void handlePolledDataVarioString(int registerNumber, Object quantity) {
        switch (CHANNELS_VARIOSTRING.get(registerNumber)) {
            case CHANNEL_PV_OPERATING_MODE:
            case CHANNEL_PV1_OPERATING_MODE:
            case CHANNEL_PV2_OPERATING_MODE:
                VSMode vsmode = StuderParser.getVSModeByCode((int) (float) quantity);
                updateState(CHANNELS_VARIOSTRING.get(registerNumber), new StringType(vsmode.name()));
                break;
            case CHANNEL_STATE_VARIOSTRING:
                State vsstate = StuderParser.getStateByCode((int) (float) quantity);
                updateState(CHANNELS_VARIOSTRING.get(registerNumber), new StringType(vsstate.name()));
                break;
            default:
                updateState(CHANNELS_VARIOSTRING.get(registerNumber),
                        new QuantityType<>((float) quantity, UNIT_CHANNELS_VARIOSTRING.get(registerNumber)));
        }
    }

    /**
     * This method is called each time new data has been polled from the VarioTrack slave
     * The register array is first parsed, then each of the channels are updated
     * to the new values
     */
    protected void handlePolledDataVarioTrack(int registerNumber, Object quantity) {
        switch (CHANNELS_VARIOTRACK.get(registerNumber)) {
            case CHANNEL_MODEL_VARIOTRACK:
                VTType type = StuderParser.getVTTypeByCode((int) (float) quantity);
                updateState(CHANNELS_VARIOTRACK.get(registerNumber), new StringType(type.name()));
                break;

            case CHANNEL_OPERATING_MODE:
                VTMode vtmode = StuderParser.getVTModeByCode((int) (float) quantity);
                updateState(CHANNELS_VARIOTRACK.get(registerNumber), new StringType(vtmode.name()));
                break;

            case CHANNEL_STATE_VARIOTRACK:
                State vtstate = StuderParser.getStateByCode((int) (float) quantity);
                updateState(CHANNELS_VARIOTRACK.get(registerNumber), new StringType(vtstate.name()));
                break;
            default:
                updateState(CHANNELS_VARIOTRACK.get(registerNumber),
                        new QuantityType<>((float) quantity, UNIT_CHANNELS_VARIOTRACK.get(registerNumber)));
        }
    }

    /**
     * This method is called each time new data has been polled from the Xtender slave
     * The register array is first parsed, then each of the channels are updated
     * to the new values
     */
    protected void handlePolledDataXtender(int registerNumber, Object quantity) {
        switch (CHANNELS_XTENDER.get(registerNumber)) {
            case CHANNEL_OPERATING_STATE:
                ModeXtender mode = StuderParser.getModeXtenderByCode((int) (float) quantity);
                updateState(CHANNELS_XTENDER.get(registerNumber), new StringType(mode.name()));
                break;
            case CHANNEL_STATE_INVERTER:
                State xtstate = StuderParser.getStateByCode((int) (float) quantity);
                updateState(CHANNELS_XTENDER.get(registerNumber), new StringType(xtstate.name()));
                break;
            default:
                updateState(CHANNELS_XTENDER.get(registerNumber),
                        new QuantityType<>((float) quantity, UNIT_CHANNELS_XTENDER.get(registerNumber)));
        }
    }

    /**
     * Handle errors received during communication
     */
    protected void handleError(AsyncModbusFailure<ModbusReadRequestBlueprint> failure) {
        // Ignore all incoming data and errors if configuration is not correct
        if (hasConfigurationError() || getThing().getStatus() == ThingStatus.OFFLINE) {
            return;
        }
        String msg = failure.getCause().getMessage();
        String cls = failure.getCause().getClass().getName();
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                String.format("Error with read: %s: %s", cls, msg));
    }

    /**
     * Returns true, if we're in a CONFIGURATION_ERROR state
     *
     * @return
     */
    protected boolean hasConfigurationError() {
        ThingStatusInfo statusInfo = getThing().getStatusInfo();
        return statusInfo.getStatus() == ThingStatus.OFFLINE
                && statusInfo.getStatusDetail() == ThingStatusDetail.CONFIGURATION_ERROR;
    }

    /**
     * Reset communication status to ONLINE if we're in an OFFLINE state
     */
    protected void resetCommunicationError() {
        ThingStatusInfo statusInfo = thing.getStatusInfo();
        if (ThingStatus.OFFLINE.equals(statusInfo.getStatus())
                && ThingStatusDetail.COMMUNICATION_ERROR.equals(statusInfo.getStatusDetail())) {
            updateStatus(ThingStatus.ONLINE);
        }
    }
}