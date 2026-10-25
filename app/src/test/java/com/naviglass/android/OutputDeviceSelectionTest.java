package com.naviglass.android;
import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class OutputDeviceSelectionTest {
    private OutputDeviceSelection.Device d(int id,String name,String address){return new OutputDeviceSelection.Device(id,11,name,address);}
    @Test public void restoredUsbUsesStableIdentityAfterItsRuntimeIdChanges(){
        OutputDeviceSelection.Device saved=d(1,"DAC","usb-address"),reconnected=d(9,"DAC","usb-address");
        assertSame(reconnected,OutputDeviceSelection.find(Arrays.asList(d(2,"Other DAC","other"),reconnected),saved,-1));
    }
    @Test public void uniquelyNamedDacRestoresWhenTheUsbPortAddressChanges(){
        OutputDeviceSelection.Device saved=d(1,"DAC","old-port"),reconnected=d(7,"DAC","new-port");
        assertSame(reconnected,OutputDeviceSelection.find(Collections.singletonList(reconnected),saved,-1));
    }
    @Test public void missingOrAmbiguousDacsUseSystemDefault(){
        OutputDeviceSelection.Device saved=d(1,"DAC","");
        assertNull(OutputDeviceSelection.find(Collections.emptyList(),saved,-1));
        assertNull(OutputDeviceSelection.find(Arrays.asList(d(2,"DAC",""),d(3,"DAC","")),saved,-1));
    }
    @Test public void explicitSelectionOfIdenticalDevicesRemainsValidDuringTheSession(){
        OutputDeviceSelection.Device selected=d(2,"DAC","");
        assertSame(selected,OutputDeviceSelection.find(Arrays.asList(selected,d(3,"DAC","")),selected,2));
        assertNull(OutputDeviceSelection.find(Arrays.asList(selected,d(3,"DAC","")),null,-1));
    }
}
