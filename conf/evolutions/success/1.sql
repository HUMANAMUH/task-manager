
# --- !Ups

create table "task" ("id" INTEGER PRIMARY KEY NOT NULL,"pool" VARCHAR(254) NOT NULL,"type" VARCHAR(254) NOT NULL,"key" VARCHAR(254) NOT NULL,"group" VARCHAR(254),"create_time" INTEGER NOT NULL,"options" VARCHAR(254) NOT NULL,"status" VARCHAR(254) NOT NULL,"scheduled_at" INTEGER NOT NULL,"scheduled_time" INTEGER NOT NULL,"start_time" INTEGER,"end_time" INTEGER,"try_count" INTEGER NOT NULL,"try_limit" INTEGER NOT NULL,"timeout" INTEGER NOT NULL,"log" VARCHAR(254) NOT NULL,"timeout_at" INTEGER);
create index "idx_group_sched" on "task" ("pool","group","scheduled_at");
create index "idx_query" on "task" ("pool","type","key");
create index "idx_status" on "task" ("status","scheduled_time");
create index "idx_type_sched" on "task" ("pool","type","scheduled_at");

# --- !Downs

drop table "task";

     