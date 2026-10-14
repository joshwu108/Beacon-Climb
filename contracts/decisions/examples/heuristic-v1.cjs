const {createHash}=require('node:crypto');
// Synthetic fixture policy. Production BC-0110 must publish its own code digest.
function rank(snapshot,record){
 const {challenge_weight,grade_window,novelty_weight,other_weights}=record.policy_config;
 if(other_weights!==0)throw Error('fixture only supports zero other weights');
 const target=snapshot.user_features.target_grade_numeric;
 return snapshot.candidates.filter(c=>c.eligible).map(c=>{
  const score=challenge_weight*(1-Math.min(1,Math.abs(c.features.grade_numeric-target)/grade_window))+novelty_weight*c.features.novelty;
  const tie=createHash('sha256').update(`${record.policy_version}|${record.user_id}|${record.session_id}|${c.route_variant_id}`).digest('hex');
  return {route_variant_id:c.route_variant_id,score,tie};
 }).sort((a,b)=>b.score-a.score||a.tie.localeCompare(b.tie)).slice(0,record.request_constraints.max_routes).map((r,i)=>({route_variant_id:r.route_variant_id,position:i+1,score:r.score,reason_codes:['COMPATIBLE_ANGLE','NOVEL_ROUTE','COLD_START_HEURISTIC']}));
}
module.exports={rank};
