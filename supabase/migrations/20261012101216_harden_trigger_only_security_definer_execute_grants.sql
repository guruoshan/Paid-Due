begin;
revoke execute on function private.notify_admins_of_confirmed_day_close() from public;
revoke execute on function private.notify_admins_of_confirmed_day_close() from anon;
revoke execute on function private.notify_admins_of_confirmed_day_close() from authenticated;
revoke execute on function private.notify_cashier_of_permission_change() from public;
revoke execute on function private.notify_cashier_of_permission_change() from anon;
revoke execute on function private.notify_cashier_of_permission_change() from authenticated;
commit;
