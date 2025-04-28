# For Generating Verilog files

RTL_SIM_DIR = $(BUILD_DIR)/rtl_sim
RTL_PERF_DIR = $(BUILD_DIR)/rtl_perf

SCALA_SRC = $(shell find $(PRJ)/src -name "*.scala")

SIM_VERILOG_SRC = $(RTL_SIM_DIR)/SimTop.sv
PERF_VERILOG_SRC = $(RTL_PERF_DIR)/PerfTop.sv

$(SIM_VERILOG_SRC): $(SCALA_SRC)
	@echo "Generating Verilog files..."
	$(call git_commit, "generate verilog")
	mkdir -p $(RTL_SIM_DIR)
	mill -i $(PRJ).runMain Elaborate --target-dir $(RTL_SIM_DIR)
	sed -i '/firrtl_black_box_resource_files.f/, $$d' $@
	@echo "Verilog files generated in $(RTL_SIM_DIR)"

$(PERF_VERILOG_SRC): $(SCALA_SRC)
	@echo "Generating Verilog files..."
	$(call git_commit, "generate verilog")
	mkdir -p $(RTL_PERF_DIR)
	mill -i $(PRJ).runMain Elaborate --target-dir $(RTL_PERF_DIR) --perf
	sed -i '/firrtl_black_box_resource_files.f/, $$d' $@
	@echo "Verilog files generated in $(RTL_PERF_DIR)"

verilog: $(SIM_VERILOG_SRC) $(PERF_VERILOG_SRC)

test:
	mill -i $(PRJ).test

reformat:
	mill -i __.reformat

checkformat:
	mill -i __.checkFormat

bsp:
	mill -i mill.bsp.BSP/install

idea:
	mill -i mill.idea.GenIdea/idea

help:
	mill -i $(PRJ).runMain Elaborate --help

.PHONY: verilog