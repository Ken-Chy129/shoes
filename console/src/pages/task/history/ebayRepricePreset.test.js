const fs = require('fs');
const path = require('path');
const {
    STORAGE_PREFIX,
    isRememberedEbayRepriceTask,
    compareTaskIds,
    extractEbayRepriceFormValues,
    parseEbayRepriceTaskParams,
    readEbayRepricePreset,
    saveEbayRepricePreset,
    shouldApplyServerEbayRepricePreset,
} = require('./ebayRepricePreset');

const createMemoryStorage = () => {
    const map = new Map();
    return {
        getItem: (key) => (map.has(key) ? map.get(key) : null),
        setItem: (key, value) => { map.set(key, String(value)); },
        removeItem: (key) => { map.delete(key); },
    };
};

describe('eBay repricing last-applied preset persistence', () => {
    it('remembers the three eBay repricing task types and ignores unrelated ones', () => {
        expect(isRememberedEbayRepriceTask('ebay_profit_price')).toBe(true);
        expect(isRememberedEbayRepriceTask('ebay_promo_profit_price')).toBe(true);
        expect(isRememberedEbayRepriceTask('ebay_price_sync')).toBe(true);
        expect(isRememberedEbayRepriceTask('ebay_bulk_listing')).toBe(false);
        expect(isRememberedEbayRepriceTask('price_down')).toBe(false);
    });

    it('compares 19-digit snowflake task IDs without losing precision', () => {
        expect(compareTaskIds('2117311091619442691', '2117311091619442690')).toBe(1);
        expect(compareTaskIds('2117311091619442690', '2117311091619442690')).toBe(0);
        expect(compareTaskIds('2117311091619442689', '2117311091619442690')).toBe(-1);
        expect(compareTaskIds('1000', '999')).toBe(1);
        expect(compareTaskIds(null, '100')).toBe(-1);
    });

    it('round-trips eBay profit repricing form values including optional switches and ignores Excel uploads', () => {
        const storage = createMemoryStorage();
        const formValues = {
            profitIntervalHours: 2,
            agFreights: [20, 25, 30, 35, 45],
            nonAgFreights: [24, 30, 36, 42, 54],
            profitThreshold: 800,
            profitLow: 35,
            profitHigh: 60,
            adRateAdjustEnabled: true,
            adRateAdjust: -1.5,
            leadEnabled: true,
            leadMinSkuCount: 4,
            leadProfit: -10,
            ebayProfitExcel: [{name: 'rules.xlsx'}],
        };

        saveEbayRepricePreset('ebay_profit_price', formValues, {taskId: '2117311091619442690'}, storage);
        const restored = readEbayRepricePreset('ebay_profit_price', storage);

        expect(restored).toEqual({
            taskId: '2117311091619442690',
            lastKnownServerTaskId: '2117311091619442690',
            values: {
                profitIntervalHours: 2,
                agFreights: [20, 25, 30, 35, 45],
                nonAgFreights: [24, 30, 36, 42, 54],
                profitThreshold: 800,
                profitLow: 35,
                profitHigh: 60,
                adRateAdjustEnabled: true,
                adRateAdjust: -1.5,
                leadEnabled: true,
                leadMinSkuCount: 4,
                leadProfit: -10,
            },
        });
        expect(restored.values.ebayProfitExcel).toBeUndefined();
    });

    it('clears optional values when switches are disabled', () => {
        const extracted = extractEbayRepriceFormValues('ebay_profit_price', {
            profitIntervalHours: 1,
            agFreights: [25, 25, 25, 25, 25],
            nonAgFreights: [25, 25, 25, 25, 25],
            profitThreshold: 500,
            profitLow: 20,
            profitHigh: 40,
            adRateAdjustEnabled: false,
            adRateAdjust: -2,
            leadEnabled: false,
            leadMinSkuCount: 3,
            leadProfit: 0,
        });

        expect(extracted.adRateAdjustEnabled).toBe(false);
        expect(extracted.adRateAdjust).toBeUndefined();
        expect(extracted.leadEnabled).toBe(false);
        expect(extracted.leadMinSkuCount).toBeUndefined();
        expect(extracted.leadProfit).toBeUndefined();
    });

    it('parses server task params JSON for profit, promo, and price-sync tasks', () => {
        const profitParams = JSON.stringify({
            intervalHours: 3,
            agFreights: [18, 22, 26, 30, 38],
            nonAgFreights: [20, 25, 30, 35, 45],
            profitThreshold: 600,
            profitLow: 25,
            profitHigh: 50,
            adRateAdjust: 2,
            leadMinSkuCount: 5,
            leadProfit: 0,
            ruleCount: 12,
        });
        expect(parseEbayRepriceTaskParams('ebay_profit_price', profitParams)).toEqual({
            profitIntervalHours: 3,
            agFreights: [18, 22, 26, 30, 38],
            nonAgFreights: [20, 25, 30, 35, 45],
            profitThreshold: 600,
            profitLow: 25,
            profitHigh: 50,
            adRateAdjustEnabled: true,
            adRateAdjust: 2,
            leadEnabled: true,
            leadMinSkuCount: 5,
            leadProfit: 0,
        });

        // Legacy profit task without 5-bucket freights should be ignored cleanly.
        expect(parseEbayRepriceTaskParams('ebay_profit_price', JSON.stringify({
            intervalHours: 1,
            defaultProfit: 30,
            freight: 25,
        }))).toBeNull();

        expect(parseEbayRepriceTaskParams('ebay_promo_profit_price', JSON.stringify({
            intervalHours: 2,
            discountPercent: 15,
            sellerSharePercent: 70,
            defaultProfit: -20,
            freight: 28,
        }))).toEqual({
            profitIntervalHours: 2,
            promoDiscountPercent: 15,
            promoSellerSharePercent: 70,
            defaultProfit: -20,
            profitFreight: 28,
        });

        expect(parseEbayRepriceTaskParams('ebay_price_sync', JSON.stringify({
            intervalHours: 4,
            priceMultiplier: 1.15,
            priceAddition: 280,
        }))).toEqual({
            intervalHours: 4,
            priceMultiplier: 1.15,
            priceAddition: 280,
        });
    });

    it('prefers newer server tasks while preserving local edits when server has not advanced', () => {
        const storage = createMemoryStorage();
        const base = {
            profitIntervalHours: 1,
            agFreights: [25, 25, 25, 25, 25],
            nonAgFreights: [25, 25, 25, 25, 25],
            profitThreshold: 500,
            profitLow: 20,
            profitHigh: 40,
            adRateAdjustEnabled: false,
            leadEnabled: false,
        };

        // Empty local cache -> apply server latest.
        expect(shouldApplyServerEbayRepricePreset(null, '2117311091619442690')).toBe(true);

        // Save succeeded task #2690, then a draft blocked by mutex (no taskId).
        saveEbayRepricePreset('ebay_profit_price', base, {taskId: '2117311091619442690'}, storage);
        saveEbayRepricePreset('ebay_profit_price', {...base, profitLow: 99}, {}, storage);
        const draft = readEbayRepricePreset('ebay_profit_price', storage);
        expect(draft.values.profitLow).toBe(99);
        expect(draft.lastKnownServerTaskId).toBe('2117311091619442690');

        // Same or older server task does not overwrite the local draft.
        expect(shouldApplyServerEbayRepricePreset(draft, '2117311091619442690')).toBe(false);
        expect(shouldApplyServerEbayRepricePreset(draft, '2117311091619442689')).toBe(false);
        // Newer server task (e.g. created on another browser) wins.
        expect(shouldApplyServerEbayRepricePreset(draft, '2117311091619442691')).toBe(true);
    });

    it('handles corrupted localStorage safely', () => {
        const storage = createMemoryStorage();
        storage.setItem(`${STORAGE_PREFIX}ebay_profit_price`, '{broken json');
        expect(readEbayRepricePreset('ebay_profit_price', storage)).toBeNull();
    });

    it('wires preset restore and save into the task creation modal', () => {
        const page = fs.readFileSync(path.join(__dirname, 'index.tsx'), 'utf8');
        expect(page).toContain('readEbayRepricePreset(taskType)');
        expect(page).toContain('parseEbayRepriceTaskParams(taskType, latest.params)');
        expect(page).toContain('saveEbayRepricePreset(submittedTaskType, values');
        expect(page).toContain("saveEbayRepricePreset('ebay_price_sync', values");
        expect(page).toContain('createFormDirtyRef.current = true');
    });
});
