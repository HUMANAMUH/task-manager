
# --- !Ups

create table "task" ("id" INTEGER PRIMARY KEY NOT NULL,"pool" VARCHAR(254) NOT NULL,"type" VARCHAR(254) NOT NULL,"key" VARCHAR(254) NOT NULL,"options" VARCHAR(254) NOT NULL,"status" VARCHAR(254) NOT NULL,"start_time" INTEGER,"end_time" INTEGER,"tryCount" INTEGER NOT NULL,"tryLimit" INTEGER NOT NULL,"timeout" INTEGER NOT NULL,"log" VARCHAR(254) NOT NULL);
create index "idx_query" on "task" ("pool","type","key");
create table "task_pending" ("id" INTEGER PRIMARY KEY NOT NULL,"time" INTEGER NOT NULL,constraint "id" foreign key("id") references "task"("id") on update NO ACTION on delete CASCADE);
create table "task_success" ("id" INTEGER PRIMARY KEY NOT NULL,"time" INTEGER NOT NULL,constraint "id" foreign key("id") references "task"("id") on update NO ACTION on delete CASCADE);
create table "task_fail" ("id" INTEGER PRIMARY KEY NOT NULL,"time" INTEGER NOT NULL,constraint "id" foreign key("id") references "task"("id") on update NO ACTION on delete CASCADE);
create table "task_block" ("id" INTEGER PRIMARY KEY NOT NULL,"time" INTEGER NOT NULL,constraint "id" foreign key("id") references "task"("id") on update NO ACTION on delete CASCADE);

# --- !Downs

drop table "task_block";
drop table "task_fail";
drop table "task_success";
drop table "task_pending";
drop table "task";

     