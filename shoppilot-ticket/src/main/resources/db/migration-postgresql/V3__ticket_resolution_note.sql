-- UX-03：把坐席结单意见持久化，买家详情才能看到“处理结论”。
ALTER TABLE tickets ADD COLUMN resolution_note VARCHAR(500);
