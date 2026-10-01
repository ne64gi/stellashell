package net.fuyumori.stellashell;

import android.content.Context;

final class ErrorText {
    static String localize(Context context,String message){
        if(message==null)return context.getString(R.string.ui_unknown_error);
        if(message.startsWith("ERROR:"))return localize(context,message.substring(6).trim());
        if(message.startsWith("Session changed")||message.startsWith("Virtual display session unavailable"))return context.getString(R.string.displays_session_changed);
        if(message.startsWith("Session termination requested"))return context.getString(R.string.displays_close_unconfirmed);
        if(message.contains("Always-on-top unsupported"))return context.getString(R.string.window_pin_unsupported);
        if(message.contains("Always-on-top requires a window"))return context.getString(R.string.window_pin_window_only);
        if(message.contains("Always-on-top was not applied"))return context.getString(R.string.window_pin_rejected);
        if(message.contains("Grouped windows cannot be pinned"))return context.getString(R.string.window_pin_grouped);
        String rollback=" / Some settings could not be restored. Use Restore to retry.";
        if(message.endsWith(rollback)&&message.length()>rollback.length())return localize(context,message.substring(0,message.length()-rollback.length()))+context.getString(R.string.ui_some_settings_could_not_be_restored_use_restore_to_retry);
        if(message.startsWith(" / Some settings could not be restored. Use Restore to retry."))return context.getString(R.string.ui_some_settings_could_not_be_restored_use_restore_to_retry)+message.substring(61);
        if(message.startsWith("The window closed or moved to another display"))return context.getString(R.string.ui_the_window_closed_or_moved_to_another_display)+message.substring(45);
        if(message.startsWith("Will not redirect to the phone display"))return context.getString(R.string.ui_will_not_redirect_to_the_phone_display)+message.substring(38);
        if(message.startsWith("The external display is disconnected"))return context.getString(R.string.ui_the_external_display_is_disconnected)+message.substring(36);
        if(message.startsWith("Shizuku API 13 or later is required"))return context.getString(R.string.ui_shizuku_api_13_or_later_is_required)+message.substring(35);
        if(message.startsWith("Could not find the launched window"))return context.getString(R.string.ui_could_not_find_the_launched_window)+message.substring(34);
        if(message.startsWith("The main display is not supported"))return context.getString(R.string.ui_the_main_display_is_not_supported)+message.substring(33);
        if(message.startsWith("Enable desktop features first"))return context.getString(R.string.ui_enable_desktop_features_first)+message.substring(29);
        if(message.startsWith("Could not verify settings: "))return context.getString(R.string.ui_could_not_verify_settings)+message.substring(27);
        if(message.startsWith("Unsupported windowing mode"))return context.getString(R.string.ui_unsupported_windowing_mode)+message.substring(26);
        if(message.startsWith("Could not close the window"))return context.getString(R.string.ui_could_not_close_the_window)+message.substring(26);
        if(message.startsWith("Unsupported setting value"))return context.getString(R.string.ui_unsupported_setting_value)+message.substring(25);
        if(message.startsWith("The display is too small"))return context.getString(R.string.ui_the_display_is_too_small)+message.substring(24);
        if(message.startsWith("Operation was rejected"))return context.getString(R.string.ui_operation_was_rejected)+message.substring(22);
        if(message.startsWith("Invalid app component"))return context.getString(R.string.ui_invalid_app_component)+message.substring(21);
        if(message.startsWith("Unsupported operation"))return context.getString(R.string.ui_unsupported_operation)+message.substring(21);
        if(message.startsWith("Operation timed out"))return context.getString(R.string.ui_operation_timed_out)+message.substring(19);
        if(message.startsWith("No external display"))return context.getString(R.string.ui_no_external_display)+message.substring(19);
        return message;
    }
}
