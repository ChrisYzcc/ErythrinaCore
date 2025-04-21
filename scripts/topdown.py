"""
    For TopDown Analysis
    Author: yzcc
"""

import re
import os

NPC_HOME = os.environ.get("NPC_HOME")

pattern = r"topdown_(\w+):\s*(\d+)"
topdown_file = os.path.join(NPC_HOME, "build", "stderr.log")
topdown_res_file = os.path.join(NPC_HOME, "build", "topdown.txt")

with open(topdown_file, "r") as f:
    lines = f.readlines()
    topdown = {}
    for line in lines:
        match = re.search(pattern, line)
        if match:
            key = match.group(1)
            value = int(match.group(2))
            topdown[key] = value

    topdown_res = {}
    topdown_res["FrontendBound"] = topdown["FetchBubbles"] / topdown["TotalSlots"]
    topdown_res["BadSpeculation"] = (topdown["SlotsIssued"] - topdown["SlotsRetired"] + topdown["RecoveryBubbles"]) / topdown["TotalSlots"]
    topdown_res["Retiring"] = topdown["SlotsRetired"] / topdown["TotalSlots"]
    topdown_res["BackendBound"] = 1 - topdown_res["FrontendBound"] - topdown_res["BadSpeculation"] - topdown_res["Retiring"]

with open(topdown_res_file, "w") as f:
    for key, value in topdown_res.items():
        f.write(f"{key}: {value:.2%}\n")
print(f"Writing topdown result to {topdown_res_file}")