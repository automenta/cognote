import idService from '../lib/idService';

describe('IdService', () => {
    it('should generate a unique ID', () => {
        const id1 = idService.generateId();
        const id2 = idService.generateId();
        expect(id1).not.toBe(id2);
    });

    it('should generate a valid UUID', () => {
        const id = idService.generateId();
        expect(id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
    });
});
