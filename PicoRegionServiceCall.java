import android.os.IBinder;
import android.os.Parcel;
import android.content.res.Configuration;
import android.os.LocaleList;
import android.content.ComponentName;

/** Matches the current PICO 5.13.7 IPvrManagerService transaction, without a package install. */
public final class PicoRegionServiceCall {
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--swift-bind") && (args[1].equals("true") || args[1].equals("false"))) {
            swiftBinding(Boolean.parseBoolean(args[1])); return;
        }
        boolean status = args.length == 1 && args[0].equals("--status");
        boolean configuration = args.length == 1 && args[0].equals("--configuration");
        boolean restoreUi = args.length == 3 && args[0].equals("--restore-ui");
        if (!status && !configuration && !restoreUi &&
                (args.length != 2 || !(args[1].equals("true") || args[1].equals("false")))) {
            throw new IllegalArgumentException("Usage: COUNTRY SAVE_TO_PERSIST(true|false)");
        }
        Class<?> manager = Class.forName("android.os.ServiceManager");
        IBinder remote = (IBinder) manager.getMethod("getService", String.class)
                .invoke(null, "pvr_manager");
        if (remote == null) throw new IllegalStateException("pvr_manager unavailable");
        if (status) {
            System.out.println("SERVICE_READY descriptor=" + remote.getInterfaceDescriptor()
                    + " uid=" + android.os.Process.myUid());
            return;
        }
        if (android.os.Process.myUid() != 1000) {
            throw new SecurityException("Regional configuration calls require system UID 1000");
        }
        if (configuration || restoreUi) {
            Object activity = Class.forName("android.app.ActivityManager")
                    .getMethod("getService").invoke(null);
            Class<?> activityInterface = Class.forName("android.app.IActivityManager");
            Configuration config = (Configuration) activityInterface.getMethod("getConfiguration").invoke(activity);
            if (configuration) {
                System.out.println("CONFIG_LOCALES=" + config.getLocales().toLanguageTags());
                return;
            }
            config.setLocales(LocaleList.forLanguageTags(args[1]));
            Configuration.class.getField("userSetLocale").setBoolean(config, true);
            activityInterface.getMethod("updatePersistentConfiguration", Configuration.class).invoke(activity, config);
            IBinder alarmBinder = (IBinder) manager.getMethod("getService", String.class).invoke(null, "alarm");
            Object alarm = Class.forName("android.app.IAlarmManager$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null, alarmBinder);
            Class.forName("android.app.IAlarmManager").getMethod("setTimeZone", String.class).invoke(alarm, args[2]);
            System.out.println("UI_CONFIG_RESTORED");
            return;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("com.pvr.IPvrManagerService");
            data.writeString(args[0]);
            data.writeInt(Boolean.parseBoolean(args[1]) ? 1 : 0);
            if (!remote.transact(7, data, reply, 0)) {
                throw new UnsupportedOperationException("updateUserSettings transaction rejected");
            }
            reply.readException();
            System.out.println("CALL_RETURNED country=" + args[0] + " saveToPersist=" + args[1]);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }
    static void swiftBinding(boolean connect) throws Exception {
        if (android.os.Process.myUid() != 1000) throw new SecurityException("Swift binding requires system UID");
        IBinder window = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "window");
        IBinder api;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("android.view.IWindowManager");
            if (!window.transact(10003, data, reply, 0) || reply.readInt() != 1) throw new IllegalStateException("PICO API layer unavailable");
            api = reply.readStrongBinder();
        } finally { data.recycle(); reply.recycle(); }
        data = Parcel.obtain();
        try {
            data.writeInterfaceToken("com.pico.api.app.IApiLayer");
            data.writeInt(1);
            new ComponentName("com.pvr.swift", "com.pvr.swift.service.SwiftService").writeToParcel(data, 0);
            data.writeInt(connect ? 1 : 0);
            if (!api.transact(8, data, null, IBinder.FLAG_ONEWAY)) throw new IllegalStateException("Swift binding rejected");
            System.out.println("SWIFT_BIND_REQUESTED=" + connect);
        } finally { data.recycle(); }
    }
}
