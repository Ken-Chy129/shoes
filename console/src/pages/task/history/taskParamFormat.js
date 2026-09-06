const formatRatePercent = (value) => {
    const percent = Number(value) * 100;
    return `${Number(percent.toFixed(10))}%`;
};

module.exports = {formatRatePercent};
