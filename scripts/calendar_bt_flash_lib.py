"""PlatformIO post script: link the flash-resident BT controller library.

With CONFIG_BT_CTRL_RUN_IN_FLASH_ONLY=y (calendar envs, platformio.ini) the
custom_sdkconfig core rebuild lays the controller out for flash, but the
Arduino framework's saved link list still names "-lbtdm_app" (the IRAM
variant). At runtime that library refuses to start: "Controller lib error,
need flash lib" / esp_bt_controller_init -11. ESP-IDF's own CMake would pick
libbtdm_app_flash.a (components/bt/controller/CMakeLists.txt); do the same
here, keeping the library's position in the link order.
"""

import os

Import("env")  # noqa: F821  # type: ignore[name-defined]

custom = env.GetProjectOption("custom_sdkconfig", "")  # noqa: F821
if "CONFIG_BT_CTRL_RUN_IN_FLASH_ONLY=y" in custom:
    idf_dir = env.PioPlatform().get_package_dir("framework-espidf")  # noqa: F821
    mcu = env.BoardConfig().get("build.mcu", "esp32c3")  # noqa: F821
    flash_lib = os.path.join(
        idf_dir, "components", "bt", "controller", "lib_esp32c3_family", mcu, "libbtdm_app_flash.a"
    )
    if not os.path.isfile(flash_lib):
        print(f"WARNING [calendar_bt_flash_lib.py]: {flash_lib} not found; BLE will not start")
    else:
        libs = list(env.get("LIBS", []))  # noqa: F821
        swapped = [env.File(flash_lib) if str(lib) in ("-lbtdm_app", "btdm_app") else lib for lib in libs]  # noqa: F821
        if swapped != libs:
            env.Replace(LIBS=swapped)  # noqa: F821
            print(f"Calendar: linking flash BT controller {flash_lib}")
        else:
            print("WARNING [calendar_bt_flash_lib.py]: -lbtdm_app not in LIBS; nothing swapped")

    # The saved Arduino link flags also still map controller functions onto
    # their ROM copies. ESP-IDF drops these scripts in flash-only mode
    # (components/esp_rom/CMakeLists.txt); keeping them makes the flash
    # controller call a mix of ROM and flash code and fault in r_rwip_init.
    rom_bt_scripts = {f"{mcu}.rom.bt_funcs.ld", f"{mcu}.rom.eco3_bt_funcs.ld", f"{mcu}.rom.eco7_bt_funcs.ld"}
    flags = [str(f) for f in env.get("LINKFLAGS", [])]  # noqa: F821
    kept, dropped, i = [], [], 0
    while i < len(flags):
        if flags[i] == "-T" and i + 1 < len(flags) and flags[i + 1] in rom_bt_scripts:
            dropped.append(flags[i + 1])
            i += 2
            continue
        if flags[i].startswith("-T") and flags[i][2:] in rom_bt_scripts:
            dropped.append(flags[i][2:])
            i += 1
            continue
        kept.append(flags[i])
        i += 1
    if dropped:
        env.Replace(LINKFLAGS=kept)  # noqa: F821
        print(f"Calendar: dropped ROM BT linker scripts {', '.join(dropped)}")
    else:
        print("WARNING [calendar_bt_flash_lib.py]: no ROM BT linker scripts found in LINKFLAGS")
