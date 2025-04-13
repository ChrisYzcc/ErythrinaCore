# For Generating Verilator Package and Testing

VERILATOR = verilator
GTKWAVE = gtkwave

TOP_NAME = SimTop
OBJ_DIR = $(BUILD_DIR)/obj_dir/$(TOP_NAME)

# Emulator files
EMU_DIR = $(NPC_HOME)/emulator
EMU_CSRC = $(shell find $(EMU_DIR) -name "*.c" -or -name "*.cpp")

CFLG += -I$(EMU_DIR)/include
VFLG += --exe -cc --trace-fst -O3 --build -j 4 -CFLAGS "${CFLG}" --autoflush

ifeq ($(TOP_NAME), SimTop)
VSRCS ?= $(shell find $(abspath $(RTL_SIM_DIR)) -name "*.v" -or -name "*.sv")
$(VSRCS): $(SIM_VERILOG_SRC)
endif

verilate:
	mkdir -p $(OBJ_DIR)
	$(VERILATOR) $(VFLG) --Mdir $(OBJ_DIR) --top-module $(TOP_NAME) $(VSRCS) $(EMU_CSRC)
.PHONY: verilate