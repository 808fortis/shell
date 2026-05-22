use rusb::{Context, Device, DeviceHandle, UsbContext};
use std::time::Duration;

const TIMEOUT: Duration = Duration::from_secs(5);

const VENDORS: &[(u16, &str, &str)] = &[
    (0x18d1, "Google",       "tensor"),
    (0x04e8, "Samsung",      "exynos"),
    (0x2d95, "OnePlus/Oppo", "snapdragon"),
    (0x2717, "Xiaomi",       "snapdragon"),
    (0x12d1, "Huawei",       "kirin"),
    (0x0bb4, "HTC",          "snapdragon"),
    (0x22b8, "Motorola",     "snapdragon"),
    (0x1004, "LG",           "snapdragon"),
    (0x0fce, "Sony",         "snapdragon"),
    (0x17ef, "Lenovo",       "snapdragon"),
    (0x2b4c, "ASUS",         "snapdragon"),
    (0x091e, "Garmin-ASUS",  "snapdragon"),
    (0x1f4a, "Nothing",      "snapdragon"),
    (0x1eb0, "Nothing",      "snapdragon"),
    (0x413c, "Dell",         "snapdragon"),
    (0x0489, "Foxconn",      "snapdragon"),
    (0x05c6, "Qualcomm",     "snapdragon"),
    (0x0e8d, "MediaTek",     "mediatek"),
    (0x29a6, "Intel",        "x86"),
    (0x0482, "Kyocera",      "snapdragon"),
    (0x0422, "NVIDIA",       "tegra"),
    (0x201e, "HMD/Nokia",    "snapdragon"),
    (0x1bbb, "TCL/Alcatel",  "mediatek"),
    (0x4dd7, "Karbonn",      "mediatek"),
    (0x19d2, "ZTE",          "snapdragon"),
    (0x34c5, "Google",       "tensor"),
    (0x3613, "TCL",          "snapdragon"),
    (0x42a4, "Vivo",         "snapdragon"),
];

fn vid_info(vid: u16) -> (&'static str, &'static str) {
    for &(id, brand, cpu) in VENDORS {
        if id == vid { return (brand, cpu); }
    }
    ("Unknown", "unknown")
}

fn is_android(vid: u16) -> bool {
    VENDORS.iter().any(|&(id, _, _)| id == vid)
}

#[derive(Clone, PartialEq)]
enum Mode { Normal, Fastboot, Download }

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

fn force_device(ctx: &Context, target: &Dev) {
    let Ok(list) = ctx.devices() else { return; };
    for dev in list.iter() {
        let Ok(desc) = dev.device_descriptor() else { continue; };
        if desc.vendor_id() != target.vid || desc.product_id() != target.pid { continue; }
        if dev.bus_number() != target.bus || dev.address() != target.addr { continue; }
        let Ok(handle) = open(&dev) else { return; };
        let vid = target.vid;
        let data: &[u8] = &[];
        for req in 0..=0x1F {
            for wval in [0x0000, 0x4F4E, 0x4442, 0x0F00, 0x0100, 0x0200, 0x0300, 0xEF00] {
                let _ = handle.write_control(0x40, req, wval, 0, data, TIMEOUT);
            }
        }
        if vid == 0x05c6 || vid == 0x0e8d {
            for _ in 0..5 {
                let _ = handle.write_control(0x40, 0x00, 0xEF00, 0, data, Duration::from_millis(100));
                std::thread::sleep(Duration::from_millis(50));
            }
        }
        for i in &target.ifaces {
            if i.class == 0xFF && handle.claim_interface(i.num).is_ok() {
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
                handle.release_interface(i.num).ok();
            }
        }
        return;
    }
}

fn main() {
    let ctx = match Context::new() {
        Ok(c) => c,
        Err(e) => { eprintln!("error: {e}"); return; }
    };
    let devices = scan(&ctx);
    if devices.is_empty() {
        println!("no device");
        return;
    }
    for d in &devices {
        println!("{:04x}:{:04x} {} {} bus={} addr={} mode={}",
            d.vid, d.pid, d.brand, d.cpu, d.bus, d.addr,
            match d.mode {
                Mode::Normal => "android",
                Mode::Fastboot => "fastboot",
                Mode::Download => "download",
            });
        if !d.ifaces.is_empty() {
            let labels: Vec<&str> = d.ifaces.iter().filter_map(|i| {
                if i.label.is_empty() { None } else { Some(i.label.as_str()) }
            }).collect();
            if !labels.is_empty() {
                println!("  ifaces: {}", labels.join(" "));
            }
        }
        if d.mode == Mode::Normal {
            force_device(&ctx, d);
            std::thread::sleep(Duration::from_secs(2));
            for nd in scan(&ctx) {
                if nd.vid == d.vid && nd.pid == d.pid {
                    match nd.mode {
                        Mode::Fastboot => println!("  -> fastboot OK"),
                        _ => println!("  -> still {}", if nd.mode == Mode::Normal { "android" } else { "download" }),
                    }
                }
            }
        }
    }
}
