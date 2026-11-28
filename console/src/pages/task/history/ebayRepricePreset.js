const STORAGE_PREFIX = 'shoes.ebayRepricePreset.v1.';
const FREIGHT_BUCKET_COUNT = 5;
const REMEMBERED_TASK_TYPES = new Set([
    'ebay_profit_price',
    'ebay_promo_profit_price',
    'ebay_price_sync',
]);

const isRememberedEbayRepriceTask = (taskType) => REMEMBERED_TASK_TYPES.has(taskType);

const toFiniteNumber = (value) => {
    if (value === null || value === undefined || value === '') return null;
    const num = Number(value);
    return Number.isFinite(num) ? num : null;
};

const toFreightArray = (value) => {
    if (!Array.isArray(value) || value.length !== FREIGHT_BUCKET_COUNT) return null;
    const parsed = value.map(toFiniteNumber);
    return parsed.every((n) => n !== null && n >= 0) ? parsed : null;
};

const normalizeTaskId = (value) => {
    if (value === null || value === undefined) return null;
    const text = String(value).trim();
    return /^\d+$/.test(text) ? text : null;
};

const compareTaskIds = (left, right) => {
    const a = normalizeTaskId(left);
    const b = normalizeTaskId(right);
    if (!a && !b) return 0;
    if (!a) return -1;
    if (!b) return 1;
    const strippedA = a.replace(/^0+/, '') || '0';
    const strippedB = b.replace(/^0+/, '') || '0';
    if (strippedA.length !== strippedB.length) {
        return strippedA.length > strippedB.length ? 1 : -1;
    }
    if (strippedA === strippedB) return 0;
    return strippedA > strippedB ? 1 : -1;
};

const extractEbayRepriceFormValues = (taskType, values) => {
    if (!values || typeof values !== 'object') return null;

    if (taskType === 'ebay_profit_price') {
        const profitIntervalHours = toFiniteNumber(values.profitIntervalHours);
        const agFreights = toFreightArray(values.agFreights);
        const nonAgFreights = toFreightArray(values.nonAgFreights);
        const profitThreshold = toFiniteNumber(values.profitThreshold);
        const profitLow = toFiniteNumber(values.profitLow);
        const profitHigh = toFiniteNumber(values.profitHigh);
        if (
            profitIntervalHours === null ||
            !agFreights ||
            !nonAgFreights ||
            profitThreshold === null ||
            profitLow === null ||
            profitHigh === null
        ) {
            return null;
        }
        const adRateAdjust = values.adRateAdjustEnabled ? toFiniteNumber(values.adRateAdjust) : null;
        const adRateAdjustEnabled = Boolean(values.adRateAdjustEnabled) && adRateAdjust !== null;
        const leadMinSkuCount = values.leadEnabled ? toFiniteNumber(values.leadMinSkuCount) : null;
        const leadProfit = values.leadEnabled ? toFiniteNumber(values.leadProfit) : null;
        const leadEnabled = Boolean(values.leadEnabled) && leadMinSkuCount !== null && leadProfit !== null;

        return {
            profitIntervalHours,
            agFreights,
            nonAgFreights,
            profitThreshold,
            profitLow,
            profitHigh,
            adRateAdjustEnabled,
            adRateAdjust: adRateAdjustEnabled ? adRateAdjust : undefined,
            leadEnabled,
            leadMinSkuCount: leadEnabled ? leadMinSkuCount : undefined,
            leadProfit: leadEnabled ? leadProfit : undefined,
        };
    }

    if (taskType === 'ebay_promo_profit_price') {
        const profitIntervalHours = toFiniteNumber(values.profitIntervalHours);
        const promoDiscountPercent = toFiniteNumber(values.promoDiscountPercent);
        const promoSellerSharePercent = toFiniteNumber(values.promoSellerSharePercent);
        const defaultProfit = toFiniteNumber(values.defaultProfit);
        const profitFreight = toFiniteNumber(values.profitFreight);
        if (
            profitIntervalHours === null ||
            promoDiscountPercent === null ||
            promoSellerSharePercent === null ||
            defaultProfit === null ||
            profitFreight === null
        ) {
            return null;
        }
        return {
            profitIntervalHours,
            promoDiscountPercent,
            promoSellerSharePercent,
            defaultProfit,
            profitFreight,
        };
    }

    if (taskType === 'ebay_price_sync') {
        const intervalHours = toFiniteNumber(values.intervalHours);
        const priceMultiplier = toFiniteNumber(values.priceMultiplier);
        const priceAddition = toFiniteNumber(values.priceAddition);
        if (intervalHours === null || priceMultiplier === null || priceAddition === null) {
            return null;
        }
        return {
            intervalHours,
            priceMultiplier,
            priceAddition,
        };
    }

    return null;
};

const parseEbayRepriceTaskParams = (taskType, rawParams) => {
    if (!rawParams) return null;
    let params = rawParams;
    if (typeof rawParams === 'string') {
        try {
            params = JSON.parse(rawParams);
        } catch {
            return null;
        }
    }
    if (!params || typeof params !== 'object') return null;

    if (taskType === 'ebay_profit_price') {
        const adRateAdjust = toFiniteNumber(params.adRateAdjust);
        const leadMinSkuCount = toFiniteNumber(params.leadMinSkuCount);
        const leadProfit = toFiniteNumber(params.leadProfit);
        return extractEbayRepriceFormValues(taskType, {
            profitIntervalHours: params.intervalHours ?? 1,
            agFreights: params.agFreights,
            nonAgFreights: params.nonAgFreights,
            profitThreshold: params.profitThreshold,
            profitLow: params.profitLow,
            profitHigh: params.profitHigh,
            adRateAdjustEnabled: adRateAdjust !== null,
            adRateAdjust,
            leadEnabled: leadMinSkuCount !== null && leadProfit !== null,
            leadMinSkuCount,
            leadProfit,
        });
    }

    if (taskType === 'ebay_promo_profit_price') {
        return extractEbayRepriceFormValues(taskType, {
            profitIntervalHours: params.intervalHours ?? 1,
            promoDiscountPercent: params.discountPercent,
            promoSellerSharePercent: params.sellerSharePercent,
            defaultProfit: params.defaultProfit,
            profitFreight: params.freight,
        });
    }

    if (taskType === 'ebay_price_sync') {
        return extractEbayRepriceFormValues(taskType, {
            intervalHours: params.intervalHours ?? 6,
            priceMultiplier: params.priceMultiplier,
            priceAddition: params.priceAddition ?? 250,
        });
    }

    return null;
};

const readEbayRepricePreset = (taskType, storage = typeof localStorage !== 'undefined' ? localStorage : null) => {
    if (!isRememberedEbayRepriceTask(taskType) || !storage) return null;
    try {
        const raw = storage.getItem(`${STORAGE_PREFIX}${taskType}`);
        if (!raw) return null;
        const parsed = JSON.parse(raw);
        const values = extractEbayRepriceFormValues(taskType, parsed?.values);
        if (!values) return null;
        const taskId = normalizeTaskId(parsed?.taskId);
        const lastKnownServerTaskId = normalizeTaskId(parsed?.lastKnownServerTaskId) || taskId;
        return {values, taskId, lastKnownServerTaskId};
    } catch {
        return null;
    }
};

const saveEbayRepricePreset = (
    taskType,
    values,
    meta = {},
    storage = typeof localStorage !== 'undefined' ? localStorage : null,
) => {
    if (!isRememberedEbayRepriceTask(taskType) || !storage) return null;
    const sanitized = extractEbayRepriceFormValues(taskType, values);
    if (!sanitized) return null;
    const taskId = normalizeTaskId(meta.taskId);
    const existing = readEbayRepricePreset(taskType, storage);
    const candidates = [taskId, normalizeTaskId(meta.lastKnownServerTaskId), existing?.lastKnownServerTaskId].filter(Boolean);
    const lastKnownServerTaskId = candidates.reduce(
        (best, cur) => (compareTaskIds(cur, best) > 0 ? cur : best),
        null,
    );
    const record = {
        values: sanitized,
        taskId,
        lastKnownServerTaskId,
    };
    try {
        storage.setItem(`${STORAGE_PREFIX}${taskType}`, JSON.stringify(record));
    } catch {
        // Ignore storage quota or private-mode errors.
    }
    return record;
};

const shouldApplyServerEbayRepricePreset = (localPreset, serverTaskId) => {
    if (!localPreset) return true;
    const normalizedServerId = normalizeTaskId(serverTaskId);
    if (!normalizedServerId) return false;
    return compareTaskIds(normalizedServerId, localPreset.lastKnownServerTaskId) > 0;
};

module.exports = {
    STORAGE_PREFIX,
    isRememberedEbayRepriceTask,
    compareTaskIds,
    extractEbayRepriceFormValues,
    parseEbayRepriceTaskParams,
    readEbayRepricePreset,
    saveEbayRepricePreset,
    shouldApplyServerEbayRepricePreset,
};
