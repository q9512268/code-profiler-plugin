package org.jvmmonitor.internal.agent;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.reflect.Method;
import eu.tango.energymodeller.energypredictor.CpuOnlyBestFitEnergyPredictor;
import eu.tango.energymodeller.energypredictor.EnergyPredictorInterface;
import eu.tango.energymodeller.types.energyuser.Host;
import eu.tango.energymodeller.types.energyuser.usage.HostEnergyCalibrationData;
import org.apache.commons.configuration.PropertiesConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Power implements PowerMXBean {

    /** The MXBean name. */
    public final static String POWER_MXBEAN_NAME = "org.jvmmonitor:type=Power";
    private OperatingSystemMXBean operatingSystemMXBean;
    private EnergyPredictorInterface predictor = null;
    private static final String DEFAULT_PREDICTOR_PACKAGE = "eu.tango.energymodeller.energypredictor";

    private long nanoBefore = 0;
    private long cpuBefore = 0;
    Host host = new Host(0, "localhost");
    private String inputString = "";

    /**
     * The constructor.
     */
    public Power() {
        operatingSystemMXBean = ManagementFactory.getOperatingSystemMXBean();
        nanoBefore = System.nanoTime();
        cpuBefore = getProcessCpuTime();
        host.setAvailable(true);
        host.setDiskGb(20);
        host.setRamMb(2048);
        PropertiesConfiguration config = new PropertiesConfiguration();
        config.setProperty("energy.modeller.cpu.energy.predictor.default_load", 0);
        config.setProperty("energy.modeller.cpu.energy.predictor.vm_share_rule", "DefaultEnergyShareRule");
        config.setProperty("energy.modeller.cpu.energy.predictor.consider_idle_energy", true);
        config.setProperty("energy.modeller.energy.predictor.overheadPerHostInWatts", 0);
        config.setProperty("energy.modeller.cpu.energy.predictor.datasource", "ZabbixDirectDbDataSourceAdaptor");
        config.setProperty("energy.modeller.cpu.energy.predictor.utilisation.observe_time.min", 0);
        config.setProperty("energy.modeller.cpu.energy.predictor.utilisation.observe_time.sec", 30);
        predictor = new CpuOnlyBestFitEnergyPredictor(config);     
    }

    /**
     * Gets the state indicating if monitoring SWT resources is supported.
     * 
     * @return <tt>true</tt> if monitoring SWT resources is supported
     */
    public boolean isSupported() {
        return true;
    }

    @Override
    public void setHostCalibrationData(List<HostEnergyCalibrationData> calibrationData) {
        if (calibrationData instanceof ArrayList) {
            host.setCalibrationData(((ArrayList<HostEnergyCalibrationData>) calibrationData));
        } else {
            ArrayList<HostEnergyCalibrationData> data = new ArrayList<>();
            data.addAll(calibrationData);
            host.setCalibrationData(data);
        }
    }

    @Override
    public String getHostCalibrationInputString() {
        return inputString;
    }

    @Override
    public void setHostCalibrationInputString(String calibrationData) {
        try {
        	Logger.getLogger(Power.class.getName()).log(Level.INFO, "Current calibration data is: " + calibrationData);
            ArrayList<HostEnergyCalibrationData> data = new ArrayList<>();
            String[] splitString = calibrationData.split(",");
            for (int i = 0; i < splitString.length; i = i + 2) {
                double cpu = Double.parseDouble(splitString[i]);
                double power = Double.parseDouble(splitString[i + 1]);
                HostEnergyCalibrationData item = new HostEnergyCalibrationData(cpu, 0, power);
                data.add(item);
            }
            // The calibration data is set only at the end after no parsing
            // errors etc.
            host.setCalibrationData(data);
            inputString = calibrationData;
        } catch (Exception ex) {
            // In this event fail silently
        }

    }

    @Override
    public List<HostEnergyCalibrationData> getHostCalibrationData() {
        return host.getCalibrationData();
    }

    /**
     * Gets the value for the attribute power
     * 
     * @return the value for power
     */
    public double getPower() {
        if (!host.isCalibrated()) {
            return 0;
        }
        try {
            if (predictor == null) {
                return 10;
            }
            long nanoAfter = System.nanoTime();
            long cpuAfter = getProcessCpuTime();

            double cpuPercentage = 0.0;
            if (nanoAfter > nanoBefore) {
                cpuPercentage = (cpuAfter - cpuBefore) / (double) (nanoAfter - nanoBefore);
            }

            nanoBefore = nanoAfter;
            cpuBefore = cpuAfter;

            //Apply checks to the range for CPU Usage.
            if (cpuPercentage > 1.0) {
                cpuPercentage = 1.0;
            } else if (cpuPercentage < 0) {
                cpuPercentage = 0.0;
            }            
            
            double power = 0.0;
            if (!host.isCalibrated()) {
                host.setCalibrationData(((ArrayList<HostEnergyCalibrationData>) getHostCalibrationData()));
            }
            power = predictor.predictPowerUsed(host, cpuPercentage);
            //System.out.println("power.java Usage: " + cpuPercentage + " Power: " + power);
            /**
             * Checking the power usage value is correct, this may not be the
             * case if cpu utilisation value gets reported incorrectly
             */
            if (power >= 0) {
                return power;
            } else {
                return 0;
            }
        } catch (Exception ex) {
            ex.printStackTrace();
            return 5;
        }

    }

    /**
     * This allows the power estimator to be set
     *
     * @param powerUtilisationPredictor
     *            The name of the predictor to use
     * @return The predictor to use.
     */
    public EnergyPredictorInterface getPredictor(String powerUtilisationPredictor) {
        EnergyPredictorInterface answer = null;
        try {
            if (!powerUtilisationPredictor.startsWith(DEFAULT_PREDICTOR_PACKAGE)) {
                powerUtilisationPredictor = DEFAULT_PREDICTOR_PACKAGE + "." + powerUtilisationPredictor;
            }
            answer = (EnergyPredictorInterface) (Class.forName(powerUtilisationPredictor).newInstance());
        } catch (ClassNotFoundException ex) {
            if (answer == null) {
                answer = new CpuOnlyBestFitEnergyPredictor();
            }
            Logger.getLogger(Power.class.getName()).log(Level.WARNING, "The predictor specified was not found");
        } catch (InstantiationException | IllegalAccessException ex) {
            if (answer == null) {
                answer = new CpuOnlyBestFitEnergyPredictor();
            }
            Logger.getLogger(Power.class.getName()).log(Level.WARNING, "The predictor specified did not work", ex);
        }
        return answer;
    }

    /**
     * This gets the cpu utilisation information to be used in the power model that estimates current power consumption.
     * @return
     */
    private long getProcessCpuTime() {
        try {
            if (Class.forName("com.sun.management.OperatingSystemMXBean").isInstance(operatingSystemMXBean)) {
                Method processCpuTime = operatingSystemMXBean.getClass().getDeclaredMethod("getProcessCpuTime");
                processCpuTime.setAccessible(true);
                long time = (Long) processCpuTime.invoke(operatingSystemMXBean);
                return time;
            } else {
                // FIXME Add alternative method if sun packages is not available
                System.err.println("Reflection using com.sun.management.OperatingSystemMXBean failed");
                return 0;
            }
        } catch (Exception e) {
            System.err.println("Error invoking getProcessCpuTime() by reflection: " + e.getMessage());
            return 0;
        }
    }

}
