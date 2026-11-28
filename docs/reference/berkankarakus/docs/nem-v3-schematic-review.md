# NEM_V3 ATJ2127 schematic review

## Source and confidence boundary

The user-provided `NEM_V3 原理图 20200831` PDF is a three-page board
schematic dated 2020-08-31, not an ATJ2167 datasheet. Page 1 explicitly names
an `ATJ2127-ID`; pages 2 and 3 show the display/analog sections and a
RTL8761AT-CG Bluetooth module. The document title block does not identify
MECHEN M30 or a commercial device. Treat it as a highly relevant ATJ2127
hardware reference, but do not call its wiring the M30 board until the board
identity/revision is independently matched. The [M30 user manual](https://ae01.alicdn.com/kf/S0d75602fca7a458fbef00b7e4ca728355.pdf)
does match the two-inch, 320x240 display class and documents physical buttons
and a scroll wheel, but does not document a touch interface. In contrast, this
schematic has a separate five-wire CTP/touch-panel section. That mismatch
lowers confidence that this is the exact M30 board, even though the SoC and
display size are suggestive.

## High-value schematic observations

### Shared NAND/LCD parallel bus

The LCD connector `CN1` is marked `2.0 TFT` and exposes an 8-bit data bus
`D0..D7`, plus `RD`, `WR`, `RS`, and `CS`. Its nets are:

| LCD signal | Schematic net |
|---|---|
| D0..D7 | FD0..FD7 |
| RD | NF_RDB |
| WR | NF_WRB |
| RS | NF_ALE |
| CS | LCM_CS |

Those same `FD0..FD7`, `NF_RDB`, `NF_WRB`, and `NF_ALE` nets connect to the
PFE13 NAND-flash interface. This is strong evidence that this board design
multiplexes the LCD and NAND flash over the same 8-bit parallel data/control
signals, with separate chip selects. It makes a parallel LCD transport model
more plausible for a matching board than an SPI-only model. The schematic
does not provide controller command semantics, timings, or firmware MMIO
addresses.

### ATJ2127 NAND-facing pins

The drawing maps the following multifunction pins to named flash nets:

| ATJ2127 pin | Pin function | Net |
|---:|---|---|
| 1 | GPIOA9/CE0 | NF_CEB0 |
| 3 | GPIOA20/CE1 | NF_CEB1 |
| 48 | GPIOA12/ALE | NF_ALE |
| 49 | GPIOA13/CLE | NF_CLE |
| 50 | GPIOA14/RB | NF_RB |
| 51 | GPIOA10/RDB | NF_RDB |
| 52 | GPIOA11/WRB | NF_WRB |
| 57..64 | GPIOA30/D7 .. GPIOA23/D0 | FD7..FD0 |

This is a board-level mapping only; register addresses and mux configuration
are not shown. The notes beside the NAND package also say RF1/RF2 population
depends on the NAND flash type, so that option must not be treated as a fixed
board fact without the exact build/revision.

### Removable storage, USB, display touch, and audio

- The TF socket uses `SD_CLK`, `SD_CMD`, and `SD_DATA0`, with a separate
  `SD_DET` net. The drawing routes `SD_DET` through a resistor network to
  `LRADC1`; this is a useful candidate explanation for analog card-detect
  sampling, not proof of firmware event timing or index-trigger behavior.
- USB-C D+/D- connect to the SoC's `USBDP`/`USBDM` nets. The connector also
  shows CC resistors and duplicated data contacts; it does not describe USB
  firmware state transitions.
- A separate five-wire touch interface is labelled `CTP_VCC`, `CTP_SDA`,
  `CTP_RESET`, `CTP_INT`, and `CTP_SCL`; `TP_RST` is the reset net. Do not
  confuse this touch controller with the TFT pixel bus.
- `AOUTL` and `AOUTR` leave the SoC through coupling capacitors/ferrites to
  the analog connector section. No separate audio codec is drawn on these
  pages. This raises a useful board-identity/component question but does not
  establish the codec in the actual M30; the schematic may be another board
  variant or may omit a separate sheet.
- Page 3 identifies RTL8761AT-CG and connects UART, PCM, I2C, and control
  signals. This may help interpret Bluetooth hardware only if this PCB is
  confirmed to match the M30 revision under study.

## What this changes in the investigation

1. First compare the M30 board/revision or known component and connector
   layout against this NEM_V3 drawing. The two-inch TFT and ATJ2127 match are
   suggestive, not unique identification; the unmentioned touch interface is
   counterevidence that must be resolved before adopting its wiring.
2. If matched, cross-check the `drv_lcd.drv` command/data path against the
   shared `NF_ALE`/`NF_RDB`/`NF_WRB`/`FD[0..7]` pins. This can constrain the
   LCD bus-width and transport model, but register values still need binary
   or trace evidence.
3. If matched, use the `LRADC1`/`SD_DET` path to prioritize the card-detect
   caller investigation. The schematic alone cannot show whether insertion
   causes indexing immediately, asynchronously, or only in particular UI
   states.
4. Keep this schematic separate from ATJ2167 documentation. It names
   ATJ2127-ID and gives board wiring; it is not a silicon programming manual
   or a register map.
