package net.fuyumori.stellashell;

import java.lang.reflect.Field;

/** Test-only bridge for asserting the live owner graph without adding production view accessors. */
final class ShellFixtureAccess {
    private ShellFixtureAccess() {}

    static ShellRuntime.Snapshot runtimeSnapshot() { return ShellRuntime.snapshot(); }

    static ShellController controller() {
        ShellRuntime.Binding binding = (ShellRuntime.Binding) read(ShellRuntime.class, null, "binding");
        if (binding == null) return null;
        return (ShellController) ((java.lang.ref.WeakReference<?>)read(ShellRuntime.Binding.class, binding, "session")).get();
    }

    static PhoneNavigationOwner phone() {
        ShellController controller = controller();
        return controller == null ? null : (PhoneNavigationOwner) read(ShellController.class, controller, "phone");
    }

    static PhoneSidebar sidebar() {
        PhoneNavigationOwner phone = phone();
        if (phone == null) return null;
        Object handle = read(PhoneNavigationOwner.class, phone, "sidebar");
        return handle == null ? null : (PhoneSidebar) read(handle.getClass(), handle, "value");
    }

    static PhoneTaskbar taskbar() {
        PhoneNavigationOwner phone = phone();
        if (phone == null) return null;
        Object handle = read(PhoneNavigationOwner.class, phone, "taskbar");
        return handle == null ? null : (PhoneTaskbar) read(handle.getClass(), handle, "value");
    }

    static SelectedOutputSurface output() {
        ShellController controller = controller();
        return controller == null ? null : (SelectedOutputSurface) read(ShellController.class, controller, "output");
    }

    static WindowChrome chrome() {
        SelectedOutputSurface output = output();
        return output == null ? null : (WindowChrome) read(SelectedOutputSurface.class, output, "chrome");
    }

    static TaskSession taskSession(TaskState state) {
        return (TaskSession) read(TaskState.class, state, "output");
    }

    static ShellRuntime.HomeVisibilityLease homeVisibility(HomeActivity activity) {
        return (ShellRuntime.HomeVisibilityLease) read(HomeActivity.class, activity, "homeVisibility");
    }

    private static Object read(Class<?> owner, Object target, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Cannot inspect test-owned Shell owner boundary: " + owner.getSimpleName() + "." + name, error);
        }
    }
}
