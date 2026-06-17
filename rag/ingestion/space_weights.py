SPACE_WEIGHTS = {
    "spl": 1.0,
    "siccicd": 0.9,
    "siconia-cicd": 0.9,
    "meters-software": 0.8,
    "siconia-odm": 0.7,
    "odite-research": 0.6,
}

DEFAULT_WEIGHT = 0.5


def get_weight(space_name: str) -> float:
    normalized = (space_name or "").lower().replace(" ", "-").replace("_", "-")
    return SPACE_WEIGHTS.get(normalized, DEFAULT_WEIGHT)
