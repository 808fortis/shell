use rusb::{Context, Device, DeviceHandle, UsbContext};
use std::fmt;
use std::io::Write;
use std::time::Duration;

const TIMEOUT: Duration = Duration::from_secs(5);
const WAIT: Duration = Duration::from_secs(2);

const VENDORS: &[(u16, &str, &str)] = &[
    (0x18d1, "Google",          "tensor"),
    (0x04e8, "Samsung",         "exynos"),
    (0x2d95, "OnePlus/Oppo",    "snapdragon"),
    (0x2717, "Xiaomi",          "snapdragon"),
    (0x12d1, "Huawei",          "kirin"),
    (0x0bb4, "HTC",             "snapdragon"),
    (0x22b8, "Motorola",        "snapdragon"),
    (0x1004, "LG",              "snapdragon"),
    (0x0fce, "Sony",            "snapdragon"),
    (0x17ef, "Lenovo",          "snapdragon"),
    (0x2b4c, "ASUS",            "snapdragon"),
    (0x091e, "Garmin-ASUS",     "snapdragon"),
    (0x1f4a, "Nothing",         "snapdragon"),
    (0x1eb0, "Nothing",         "snapdragon"),
    (0x413c, "Dell",            "snapdragon"),
    (0x0489, "Foxconn",         "snapdragon"),
    (0x04c5, "Fujitsu",         "snapdragon"),
    (0x05c6, "Qualcomm",        "snapdragon"),
    (0x0e8d, "MediaTek",        "mediatek"),
    (0x29a6, "Intel",           "x86"),
    (0x0482, "Kyocera",         "snapdragon"),
    (0x0422, "NVIDIA",          "tegra"),
    (0x201e, "HMD/Nokia",       "snapdragon"),
    (0x05c6, "Qualcomm",        "snapdragon"),
    (0x0e8d, "MediaTek",        "mediatek"),
    (0x1bbb, "TCL/Alcatel",     "mediatek"),
    (0x4dd7, "Karbonn",         "mediatek"),
    (0x19d2, "ZTE",             "snapdragon"),
    (0x34c5, "Google",          "tensor"),
    (0x3613, "TCL",             "snapdragon"),
    (0x42a4, "Vivo",            "snapdragon"),
];

fn vid_info(vid: u16) -> (&'static str, &'static str) {
    for &(id, brand, cpu) in VENDORS {
        if id == vid {
            return (brand, cpu);
        }
    }
    ("Unknown", "unknown")
}

fn is_android(vid: u16) -> bool {
    VENDORS.iter().any(|&(id, _, _)| id == vid)
}

#[derive(Clone, PartialEq)]
enum Mode { Normal, Fastboot, Download }

impl fmt::Display for Mode {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Mode::Normal    => write!(f, "android"),
            Mode::Fastboot  => write!(f, "fastboot"),
            Mode::Download  => write!(f, "download"),
        }
    }
}

#[derive(Clone)]
struct Dev {
    vid: u16,
    pid: u16,
    brand: String,
    cpu: String,
    bus: u8,
    addr: u8,
    mode: Mode,
    ifaces: Vec<IFace>,
}

#[derive(Clone)]
struct IFace {
    num: u8,
    class: u8,
    sub: u8,
    proto: u8,
    label: String,
}

fn iface_label(c: u8, s: u8, p: u8) -> &'static str {
    match (c, s, p) {
        (0xFF, 0x42, 0x01) => "adb",
        (0xFF, 0x42, 0x03) => "fastboot",
        (0xFF, 0x44, _)    => "qusb/edl",
        (0xFF, 0x45, _)    => "qdloader",
        (0xFF, 0x5D, _)    => "mtk",
        (0xFF, 0xFE, _)    => "mtk-preloader",
        (0xFF, 0xFF, 0xFF) => "android-vendor",
        (0x08, _, _)       => "mtp",
        (0x06, _, _)       => "ptp",
        (0x02, _, _)       => "rndis",
        (0x0A, _, _)       => "cdc-data",
        _                  => "",
    }
}

fn classify(ifaces: &[IFace]) -> Mode {
    for i in ifaces {
        if i.sub == 0x42 && i.proto == 0x03 { return Mode::Fastboot; }
        if i.class == 0xFF && matches!(i.sub, 0x44 | 0x45 | 0x5D | 0xFE) { return Mode::Download; }
    }
    Mode::Normal
}


fn scan(ctx: &Context) -> Vec<Dev> {
    let mut out = Vec::new();
    let Ok(list) = ctx.devices() else { return out; };
    for dev in list.iter() {
        let Ok(desc) = dev.device_descriptor() else { continue; };
        let vid = desc.vendor_id();
        if !is_android(vid) { continue; }
        let pid = desc.product_id();
        let (brand, cpu) = vid_info(vid);
        let ifaces: Vec<IFace> = match dev.active_config_descriptor() {
            Ok(cfg) => cfg.interfaces()
                .flat_map(|i| i.descriptors())
                .map(|d| IFace {
                    num: d.interface_number(),
                    class: d.class_code(),
                    sub: d.sub_class_code(),
                    proto: d.protocol_code(),
                    label: iface_label(d.class_code(), d.sub_class_code(), d.protocol_code()).to_string(),
                })
                .collect(),
            Err(_) => vec![],
        };
        let mode = classify(&ifaces);
        out.push(Dev {
            vid, pid,
            brand: brand.to_string(),
            cpu: cpu.to_string(),
            bus: dev.bus_number(),
            addr: dev.address(),
            mode,
            ifaces,
        });
    }
    out
}

fn open<T: UsbContext>(dev: &Device<T>) -> Result<DeviceHandle<T>, rusb::Error> {
    let h = dev.open()?;
    #[cfg(target_os = "linux")]
    if let Ok(cfg) = dev.active_config_descriptor() {
        for i in cfg.interfaces() {
            for d in i.descriptors() {
                let _ = h.detach_kernel_driver(d.interface_number());
            }
        }
    }
    Ok(h)
}

fn resolve_target(devices: &[Dev], raw: Option<&str>) -> Vec<usize> {
    let Some(s) = raw else { return (0..devices.len()).collect(); };
    if let Ok(idx) = s.parse::<usize>() {
        if idx < devices.len() { return vec![idx]; }
        eprintln!("error: index {idx} out of range (max {})", devices.len() - 1);
        std::process::exit(1);
    }
    if let Some((vs, ps)) = s.split_once(':') {
        let vid = u16::from_str_radix(vs, 16).unwrap_or(u16::MAX);
        let pid = u16::from_str_radix(ps, 16).unwrap_or(u16::MAX);
        let idxs: Vec<usize> = devices.iter().enumerate()
            .filter(|(_, d)| d.vid == vid && d.pid == pid)
            .map(|(i, _)| i).collect();
        if idxs.is_empty() {
            eprintln!("error: no device {vid:04x}:{pid:04x} found");
            std::process::exit(1);
        }
        return idxs;
    }
    eprintln!("error: invalid target '{s}' (use index or vid:pid)");
    std::process::exit(1);
}

fn list_devices(devices: &[Dev]) {
    if devices.is_empty() {
        println!("no Android devices found");
        return;
    }
    for (i, d) in devices.iter().enumerate() {
        println!("[{i}] {:<14} {:04x}:{:04x}  bus={:<3} addr={:<3} mode={:<10} cpu={}", 
            d.brand, d.vid, d.pid, d.bus, d.addr, d.mode.to_string(), d.cpu);
        if !d.ifaces.is_empty() {
            let labels: Vec<&str> = d.ifaces.iter().filter_map(|i| {
                if i.label.is_empty() { None } else { Some(i.label.as_str()) }
            }).collect();
            if !labels.is_empty() {
                println!("       ifaces: {}", labels.join(", "));
            }
        }
    }
}

fn cmd_list(ctx: &Context) {
    list_devices(&scan(ctx));
}


fn fastboot_interact(ctx: &Context, devices: &[Dev]) {
    for d in devices {
        if d.mode != Mode::Fastboot { continue; }
        println!("device {:04x}:{:04x} is in fastboot mode", d.vid, d.pid);
        let Ok(list) = ctx.devices() else { continue; };
        for dev in list.iter() {
            let Ok(desc) = dev.device_descriptor() else { continue; };
            if desc.vendor_id() != d.vid || desc.product_id() != d.pid { continue; }
            if dev.bus_number() != d.bus || dev.address() != d.addr { continue; }
            let handle = match open(&dev) {
                Ok(h) => h,
                Err(e) => { eprintln!("  open failed: {e}"); continue; }
            };
            if d.ifaces.is_empty() { continue; }
            let fb_iface = d.ifaces.iter().find(|i| i.label == "fastboot");
            let Some(fb) = fb_iface else { continue; };
            if handle.claim_interface(fb.num).is_err() { continue; }
            println!("  commands: getvar, reboot, continue, flash, boot");
            println!("  enter 'help' for full list, 'exit' to quit");
            let stdin = std::io::stdin();
            loop {
                print!("  fastboot> ");
                std::io::stdout().flush().ok();
                let mut line = String::new();
                stdin.read_line(&mut line).ok();
                let line = line.trim();
                if line.is_empty() || line == "exit" || line == "quit" { break; }
                if line == "help" {
                    println!("    getvar <var>    read variable (or 'all')");
                    println!("    reboot          reboot normally");
                    println!("    reboot-bootloader  reboot to bootloader");
                    println!("    continue        continue boot");
                    println!("    flash <part> <file>  flash partition");
                    println!("    boot <file>     boot kernel image");
                    println!("    devices         list fastboot devices");
                    println!("    exit            quit");
                    continue;
                }
                let mut cmd = line.as_bytes().to_vec();
                cmd.push(0);
                match handle.write_bulk(0x01, &cmd, TIMEOUT) {
                    Ok(_) => {},
                    Err(e) => { eprintln!("    send error: {e}"); break; }
                }
                let mut resp = vec![0u8; 1024];
                match handle.read_bulk(0x81, &mut resp, TIMEOUT) {
                    Ok(n) => {
                        let s = String::from_utf8_lossy(&resp[..n]);
                        if s.starts_with("OKAY") {
                            let val = s[4..].trim_end_matches('\0').trim();
                            if !val.is_empty() { println!("    {val}"); }
                        } else if s.starts_with("FAIL") {
                            let val = s[4..].trim_end_matches('\0').trim();
                            println!("    FAIL: {val}");
                        } else {
                            println!("    {s}");
                        }
                    },
                    Err(e) => { eprintln!("    recv error: {e}"); break; }
                }
            }
            handle.release_interface(fb.num).ok();
        }
    }
}

fn force_device(ctx: &Context, target: &Dev) {
    let Ok(list) = ctx.devices() else { return; };
    for dev in list.iter() {
        let Ok(desc) = dev.device_descriptor() else { continue; };
        if desc.vendor_id() != target.vid || desc.product_id() != target.pid { continue; }
        if dev.bus_number() != target.bus || dev.address() != target.addr { continue; }
        let handle = match open(&dev) {
            Ok(h) => h,
            Err(e) => { eprintln!("  open: {e} (try running as root)"); return; }
        };
        let vid = target.vid;
        print!("  trying usb control requests... ");
        std::io::stdout().flush().ok();
        let data: &[u8] = &[];
        for req in 0..=0x1F {
            for wval in [0x0000, 0x4F4E, 0x4442, 0x0F00, 0x0100, 0x0200, 0x0300, 0xEF00] {
                let _ = handle.write_control(0x40, req, wval, 0, data, TIMEOUT);
            }
        }
        println!("done");

        if vid == 0x05c6 || vid == 0x0e8d {
            print!("  trying edl/preloader sequence... ");
            std::io::stdout().flush().ok();
            for _ in 0..5 {
                let _ = handle.write_control(0x40, 0x00, 0xEF00, 0, data, Duration::from_millis(100));
                std::thread::sleep(Duration::from_millis(50));
            }
            println!("done");
        }

        for i in &target.ifaces {
            if i.class == 0xFF && handle.claim_interface(i.num).is_ok() {
                let extra = if i.label.is_empty() { String::new() } else { format!(" ({})", i.label) };
                print!("  interface #{}{extra}: sending reboot-bootloader... ", i.num);
                std::io::stdout().flush().ok();
                if i.sub == 0x42 && i.proto == 0x03 {
                    let _ = handle.write_bulk(0x01, b"reboot-bootloader\0", TIMEOUT);
                } else {
                    for cmd in &[b"reboot-bootloader" as &[u8], b"reboot", b"fastboot"] {
                        let _ = handle.write_control(0x40, 0x00, 0, 0, cmd, TIMEOUT);
                        let mut buf = cmd.to_vec();
                        buf.push(0);
                        let _ = handle.write_bulk(0x01, &buf, TIMEOUT);
                    }
                }
                println!("done");
                handle.release_interface(i.num).ok();
            }
        }
        return;
    }
}

fn cmd_force(ctx: &Context, raw: Option<&str>) {
    let devices = scan(ctx);
    if devices.is_empty() {
        println!("no Android devices found");
        println!("make sure the device is connected via USB");
        return;
    }
    let indices = resolve_target(&devices, raw);

    for &idx in &indices {
        let d = &devices[idx];
        println!("[{}] {:04x}:{:04x} {} cpu:{}", idx, d.vid, d.pid, d.brand, d.cpu);
        match d.mode {
            Mode::Fastboot => println!("  already in fastboot mode"),
            Mode::Download => println!("  in download/edl mode (use vendor-specific tool)"),
            Mode::Normal => {
                print!("  mode={} -> forcing fastboot... ", d.mode);
                std::io::stdout().flush().ok();
                force_device(ctx, d);
                std::thread::sleep(WAIT);
                let after = scan(ctx);
                let status = after.iter().find(|nd| nd.vid == d.vid);
                match status {
                    Some(nd) if nd.mode == Mode::Fastboot => println!("  OK  (now fastboot)"),
                    Some(nd) => println!("  FAIL (still {})", nd.mode),
                    None => {
                        let fb: Vec<&Dev> = after.iter().filter(|x| x.mode == Mode::Fastboot).collect();
                        if !fb.is_empty() {
                            println!("  OK  (new fastboot device appeared)");
                            for f in &fb {
                                println!("       {:04x}:{:04x} {} bus={} addr={}", 
                                    f.vid, f.pid, f.brand, f.bus, f.addr);
                            }
                        } else {
                            println!("  FAIL (device gone, may have rebooted)");
                        }
                    }
                }
            }
        }
    }

    let fb: Vec<Dev> = devices.iter().filter(|d| d.mode == Mode::Fastboot).cloned().collect();
    if !fb.is_empty() && raw.is_some() {
        println!();
        fastboot_interact(ctx, &fb);
    }
}

fn usage() {
    println!("usage: force-fastboot [options] [target]");
    println!();
    println!("options:");
    println!("  -l, --list    list detected Android devices");
    println!("  -h, --help    show this help");
    println!();
    println!("target (optional):");
    println!("  <index>       device index from list");
    println!("  <vid:pid>     vendor:product ID (e.g. 18d1:4ee0)");
    println!();
    println!("examples:");
    println!("  force-fastboot              scan and force all devices");
    println!("  force-fastboot -l           list only");
    println!("  force-fastboot 0            force device at index 0");
    println!("  force-fastboot 18d1:4ee0    force device by USB ID");
    println!();
    println!("note: run as root for USB access");
}

fn main() {
    let ctx = match Context::new() {
        Ok(c) => c,
        Err(e) => { eprintln!("error: USB init failed: {e}"); std::process::exit(1); }
    };

    let args: Vec<String> = std::env::args().collect();
    match args.get(1).map(|s| s.as_str()) {
        Some("-l") | Some("--list") | Some("list") => cmd_list(&ctx),
        Some("-h") | Some("--help") | Some("help") => usage(),
        Some(target) => cmd_force(&ctx, Some(target)),
        None => cmd_force(&ctx, None),
    }
}
