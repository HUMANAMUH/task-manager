
# --- !Ups

create table "task" ("id" INTEGER PRIMARY KEY NOT NULL,"pool" VARCHAR(254) NOT NULL,"type" VARCHAR(254) NOT NULL,"key" VARCHAR(254) NOT NULL,"options" VARCHAR(254) NOT NULL,"status" VARCHAR(254) NOT NULL,"start_time" INTEGER,"end_time" INTEGER,"tryCount" INTEGER NOT NULL,"tryLimit" INTEGER NOT NULL,"timeout" INTEGER NOT NULL,"log" VARCHAR(254) NOT NULL);
create unique index "idx_query" on "task" ("pool","type","key");
create index "idx_status" on "task" ("status");

# --- !Downs

drop table "task";

     