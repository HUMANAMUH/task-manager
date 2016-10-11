
# --- !Ups

create table "algo_pack" ("pool" VARCHAR(128) NOT NULL PRIMARY KEY,"type" VARCHAR(32) NOT NULL,"key" VARCHAR(128) NOT NULL,"range_l" BIGINT NOT NULL,"range_r" BIGINT NOT NULL,"create_time" BIGINT NOT NULL,"options" VARCHAR NOT NULL,"status" VARCHAR NOT NULL,"start_time" BIGINT,"end_time" BIGINT,"tryCount" INTEGER NOT NULL,"tryLimit" INTEGER NOT NULL,"log" VARCHAR NOT NULL);

# --- !Downs

drop table "algo_pack";

     